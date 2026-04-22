# JDBC-only 导入 + 稳健提速（移除 Bulk Load）设计

## 背景

当前项目曾引入 MySQL bulk load（`LOAD DATA LOCAL INFILE`）以提升导入性能，但在部分环境会遇到：

- `LOCAL INFILE` 被禁用（权限/配置/云厂商策略/代理）
- bulk 写入 staging 结果异常（例如写入 0 行、warnings 等），在 TRUNCATE 场景带来“空表风险”与用户强烈负反馈

同时，现有 JDBC 导入实现的主要瓶颈在于：

- 每个单元格反复执行多轮 `convertValue()` 猜测解析（CPU 重、对象分配多）
- 批量写入未充分利用驱动的 batch 合并能力（性能上限偏低）

本设计将导入路径统一为 JDBC，并对 JDBC 导入进行“稳健提速”，目标在 7 万行量级达到可接受的导入时间，同时保持严格的数据质量与可定位错误信息。

## 目标与非目标

- **目标**
  - **完全移除 bulk load 路径**：不再使用 `LOAD DATA LOCAL INFILE`，不再提供 bulk 开关
  - **性能目标（B 档）**：7 万行导入时间 **≤ 5 分钟**（典型数据、列数中等）
  - **严格模式默认**：类型不匹配/日期解析失败时，直接失败并返回 **第几行第几列 + 列名 + 原始值** 的错误
  - **稳定性优先**：跨环境（本地 MySQL、云 RDS、代理）尽可能一致，避免依赖 LOCAL INFILE 能力

- **非目标**
  - 不追求与 dbeaver/bulk 完全一致的极限吞吐（≤2 分钟档）
  - 不在本期重构 Excel streaming（可作为后续增强）
  - 不扩展到 MySQL 以外数据库

## 方案总览（推荐）

### 核心策略：列类型驱动的转换 + JDBC batch 写入

导入开始前，读取目标表列元数据（`INFORMATION_SCHEMA.COLUMNS`），为每个导入列构建固定的转换器（`ColumnConverter`）：

- 每个单元格只做 **一次确定性解析**
- 使用对应的 `PreparedStatement#setXxx` 绑定
- 严格模式下：解析失败直接抛出带行列定位的异常

同时对 JDBC 写入做稳健调优：

- 合理的 batchSize（按列数与内存压力选保守默认）
- JDBC URL 增加 `rewriteBatchedStatements=true` 以提升批量吞吐

## 组件与边界

### 1) 移除 bulk load 路径

需要移除或下线的能力：

- `BulkLoadImportService` 不再被调用（最终可删除类与相关测试）
- `AppConfig.bulkLoadEnabled` 配置与 `import.bulkLoadEnabled` 解析移除
- `ImportService` 不再根据 bulk 开关分流，统一走 JDBC

对用户行为：

- UI/配置层不再暴露 bulk 开关（若当前没有 UI，仅配置文件层面移除即可）

### 2) 读取目标表列元数据

在 `DbService` 增加新方法（示意）：

- `getColumnMetas(databaseId, tableName)` 返回按 `ORDINAL_POSITION` 的列信息：
  - `columnName`
  - `dataType` / `columnType`
  - `isNullable`
  - `numericPrecision` / `numericScale`

> 只在导入开始读取一次；导入过程不再重复查询元数据。

### 3) ColumnConverter（确定性、可测试）

为常见 MySQL 类型提供转换器（最小集合即可覆盖大多数业务表）：

- **整数类**：`TINYINT/SMALLINT/INT/BIGINT` → `Long`（按范围校验，溢出报错）
- **小数类**：`DECIMAL` → `BigDecimal`（严格解析；可选校验 scale）
- **文本类**：`CHAR/VARCHAR/TEXT` → `String`（保留原值；空字符串按产品语义可映射为 NULL 或空串，需与现状一致）
- **时间类**：`DATETIME/TIMESTAMP/DATE` → `LocalDateTime/LocalDate`（支持常见格式：`yyyy-MM-dd HH:mm:ss`、`yyyy/MM/dd HH:mm`、ISO 等；失败报错并回显原值）
- **布尔类**：`TINYINT(1)`/`BOOLEAN` → `Boolean` 或 `Integer(0/1)`（与表定义一致）

严格模式错误信息要求：

- `第 {rowIndex} 行，第 {colIndex} 列（{columnName}）解析失败：期望 {type}，实际值='{value}'`

### 4) 写入策略与 batch

保持现有 JDBC INSERT 语义（冲突策略不变）：

- IGNORE：`INSERT IGNORE`
- UPDATE：`ON DUPLICATE KEY UPDATE ...`
- ERROR：普通 `INSERT`（遇冲突/类型错误立即失败）

性能调优点：

- JDBC URL 增加：`rewriteBatchedStatements=true`
- batchSize 默认建议：`2000`–`5000`（按列数与单行大小调整；保持可配置）

### 5) TRUNCATE 语义说明

无论 bulk 还是 JDBC，只要使用 MySQL `TRUNCATE` 都存在隐式提交语义：

- 本设计不改变 TRUNCATE 的“不可回滚”本质
- 通过严格失败定位与更稳定的 JDBC 路径，降低“清空后失败”的概率

若未来需要“原子覆盖”，需另行设计（例如 staging rename swap），不在本期范围。

## 测试计划

### 单元测试

- `ColumnConverter` 的解析覆盖：
  - 每种类型的成功/失败样例
  - 空值与空字符串边界
  - 日期多格式支持
  - 数字溢出与 scale 边界

### 集成测试（推荐 Testcontainers MySQL）

- 7 万行不作为 CI 强制（耗时），但提供本地可运行的性能 smoke：
  - `rewriteBatchedStatements=true` 生效
  - TRUNCATE/IGNORE/UPDATE 基本语义正确

## 迁移与清理

- 现有与 bulk 相关的 spec/plan 可保留为历史，但需在 README 或变更日志中标注“已废弃”
- 删除 bulk 代码后，确保：
  - `mvn test` 通过
  - API 响应结构保持兼容（新增字段需谨慎；本设计倾向不新增 API 字段）

