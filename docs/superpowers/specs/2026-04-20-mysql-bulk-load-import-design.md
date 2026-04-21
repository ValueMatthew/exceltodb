# MySQL Bulk Load 导入优化（Excel/CSV）设计

## 背景与目标

当前导入实现主要使用 JDBC `PreparedStatement` + `addBatch/executeBatch`，并对每个单元格做 `convertValue()` 多轮解析。对 7 万行这类数据量，整体吞吐明显低于 dbeaver 等工具常见的 1–2 分钟水平。

本设计目标：

- **性能优先**：Excel/CSV 导入都走 MySQL 的 bulk load 路径，尽可能接近 dbeaver 的导入速度。
- **策略不降级**：继续支持用户选择的 **覆盖 / 更新 / 忽略** 三种冲突策略。
- **事务语义保持**：覆盖（TRUNCATE）场景保持“原子性”体验（失败回滚，不出现半导入）。
- **可观测性**：沿用现有 import heartbeat（阶段/行数/耗时），不因 bulk load 变成黑盒。

非目标（本期不做）：

- 计算真实百分比进度（bulk load 期间难以细粒度获得精确百分比）。
- 为 MySQL 以外数据库做兼容（仅面向 MySQL）。

## 方案总览（推荐）

**推荐方案：临时表 + `LOAD DATA LOCAL INFILE` + 一次性合并到目标表。**

核心思路：把“高速写入”与“冲突策略处理”解耦。先把数据高速灌入临时表，再用单条/少量 SQL 将临时表合并到目标表，以实现覆盖/更新/忽略。

### 数据流与阶段

1. **标准化输入到 CSV**
   - `.csv`：尽量直接使用（必要时修正 BOM/编码/换行/字段引号策略），形成“标准 CSV”
   - `.xlsx`：服务端读取后写出“标准 CSV”（与 `.csv` 同一规范）
2. **创建临时表**：`__import_<requestId>`（简称 `tmp`）
3. **Bulk Load 到临时表**：`LOAD DATA LOCAL INFILE ... INTO TABLE tmp`
4. **按策略合并**：`tmp` → `target`
5. **清理**：删除临时表、删除临时 CSV

Heartbeat stages 建议扩展/映射为：

- `TRUNCATE`（覆盖策略时）
- `READING`（Excel→CSV 或 CSV 标准化）
- `INSERTING`（执行 `LOAD DATA`，以及后续 merge）
- `COMMITTING`

## 冲突策略语义与 SQL 形态

约定：

- 目标表：`target`
- 临时表：`tmp`（真实表名为 `__import_<requestId>`）
- `cols...`：本次导入的列集合（按文件表头列顺序）
- 冲突判定依赖：目标表已存在的 **PK/UK**（主键或唯一键）

### 忽略（IGNORE）

语义：重复键（PK/UK 冲突）的行跳过，其余插入。

实现（推荐）：

- `INSERT IGNORE INTO target (cols...) SELECT cols... FROM tmp;`

说明：依赖目标表 PK/UK 决定重复，MySQL 处理高效。

### 更新（UPDATE / UPSERT）

语义：重复键则更新，不重复则插入。

实现（推荐）：

- `INSERT INTO target (cols...) SELECT cols... FROM tmp
   ON DUPLICATE KEY UPDATE col1=VALUES(col1), ...;`

更新列集合策略：

- 默认：更新除主键外的所有导入列
- 若当前实现已支持“全列更新”的行为，则保持一致

### 覆盖（COVER）

语义：以本次导入数据整体覆盖目标表。

实现（推荐，且与现有 TRUNCATE 原子语义一致）：

- `TRUNCATE TABLE target;`
- `INSERT INTO target (cols...) SELECT cols... FROM tmp;`

说明：

- 需明确目标表是否被外键引用。若存在外键引用，TRUNCATE 可能失败；此类表需业务侧约束或另行处理（本期不扩展复杂外键方案）。

## `LOAD DATA LOCAL INFILE` 规范

### CSV 标准格式（服务端输出/修正）

- **分隔符**：`,`（逗号）
- **换行**：`\n`
- **引号**：`"` 包裹字段；字段内 `"` 以 `""` 转义（RFC4180 风格）
- **表头**：第一行为列名（用于映射列），`LOAD DATA` 使用 `IGNORE 1 LINES`
- **空值**：空字符串映射为 `NULL`（通过 `SET col = NULLIF(@v,'')`）

### `LOAD DATA` 示例形态（伪 SQL）

> 注：实际实现中使用 `PreparedStatement` 传入文件路径，避免拼接注入；表名使用反引号并对反引号做转义。

- `LOAD DATA LOCAL INFILE ? INTO TABLE tmp`
- `CHARACTER SET utf8mb4`
- `FIELDS TERMINATED BY ',' ENCLOSED BY '\"'`
- `LINES TERMINATED BY '\n'`
- `IGNORE 1 LINES`
- `(@c1,@c2,...) SET col1=NULLIF(@c1,''), col2=NULLIF(@c2,''), ...`

### 必要前置条件

- JDBC URL 打开：`allowLoadLocalInfile=true`
- MySQL 允许 local infile：
  - `local_infile=ON`（或至少该连接允许）
- 生产环境若禁用 LOCAL：
  - 备选：服务器端 `LOAD DATA INFILE`（要求 MySQL 服务器可访问导入文件路径，通常更难；本期不作为默认）

## Excel → 临时 CSV 的性能与实现约束

原则：转换阶段保持“轻量化”，避免把大量 CPU 花在类型推断/复杂格式化上。

- `.xlsx` 读取：使用现有解析能力，输出标准 CSV
- 不做重型类型推断（BigDecimal/LocalDateTime 的多轮尝试），只做最小必要的转义/引号/空值规范化
- 临时 CSV 输出路径：沿用 `uploadTempPath`，文件名包含 `requestId`，导入完成/失败后清理

## 事务、回滚与资源清理

推荐：所有步骤在同一个连接、同一事务中执行（`autoCommit=false`）：

- `CREATE TABLE tmp ...`
- `LOAD DATA ... INTO tmp`
- 覆盖策略：`TRUNCATE target` + `INSERT INTO target SELECT ... FROM tmp`
- 更新/忽略策略：`INSERT ... SELECT ... FROM tmp ...`
- `DROP TABLE tmp`
- `COMMIT`

失败处理：

- 任意步骤异常：`ROLLBACK`
- `finally`：尽最大努力 `DROP TABLE IF EXISTS tmp`、删除临时 CSV 文件

## 可观测性（Heartbeat）

bulk load 期间建议这样更新 heartbeat：

- 开始转换/读取：`READING`
- 开始 `LOAD DATA`：`INSERTING`（`processedRows` 可先置 0）
- `LOAD DATA` 完成后：
  - 可用 `SELECT COUNT(*) FROM tmp` 更新一次 `processedRows`（可选）
- 执行 merge（插入/更新/覆盖插入）前后各更新一次 stage/message
- 提交前：`COMMITTING`
- 成功/失败：`SUCCESS/ERROR`（沿用现有 store）

## 验收标准

功能正确性：

- 覆盖/更新/忽略三策略在包含冲突行的数据集上结果与预期一致
- 覆盖策略失败回滚：失败后目标表不出现部分写入（保持“原子覆盖”体验）

性能（同库/同表结构/同索引/同网络条件下）：

- 以 7 万行作为基准：整体导入耗时显著优于 JDBC batch insert，目标接近 dbeaver 的 1–2 分钟量级

兼容性与安全：

- 若环境不允许 `LOCAL INFILE`，需明确报错提示（并提供可选降级路径的决策）

