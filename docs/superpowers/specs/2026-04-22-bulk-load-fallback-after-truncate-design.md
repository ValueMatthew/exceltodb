# MySQL Bulk Load 0 行时回退（TRUNCATE 保障表不为空）设计

## 背景

当前导入在开启 bulk load（`LOAD DATA LOCAL INFILE`）后，出现过如下故障形态：

- 用户选择 **TRUNCATE**（覆盖导入）后，目标表已被清空
- bulk load 阶段未向临时表写入任何行（`COUNT(*) == 0`）
- 最终返回“导入完成但未写入任何数据行”，导致用户感知为“无论导什么都报错”，且目标表被清空后的数据丢失风险最大

本设计目标是在 **不牺牲 bulk load 性能优势** 的同时，保证 TRUNCATE 场景的产品安全性：当 bulk load “无效/未生效”时，自动回退到 JDBC 导入路径完成补写，让表不为空。

## 目标与非目标

- **目标**
  - **优先保证表不为空**：TRUNCATE 后若 bulk load 结果为 0 行，但文件应有数据，则自动使用 JDBC 路径导入补写
  - **不做二次重试**：避免同一请求内反复 bulk 尝试造成耗时与不确定性；满足条件立即 fallback
  - **可诊断**：返回与日志中明确标注是 bulk=0 触发 fallback，并附带关键诊断信息（CSV 大小、SQL warnings 等）

- **非目标**
  - 不试图在该设计内彻底修复所有 “LOCAL INFILE 0 行” 的底层原因（环境/权限/代理/驱动差异较大）
  - 不改变现有“增量/冲突策略”语义（本设计聚焦 TRUNCATE 风险面）

## 核心思路

在 bulk load 数据流中增加一个 **“bulk 是否有效”** 判定点：

1. 生成标准 CSV（`ensureStandardCsv`）
2. 创建 staging 临时表（`CREATE TABLE tmp LIKE target`）
3. 执行 `LOAD DATA LOCAL INFILE` 写入 staging
4. `SELECT COUNT(*) FROM tmp` 得到 `stagingRows`
5. 若 `stagingRows == 0` 且判定“文件应有数据”，则 **不再 merge**，直接进入 JDBC fallback 路径进行补写

> 注意：本设计的 fallback 行为只对 **TRUNCATE** 模式生效（因为这是用户最关心“表不为空”的路径）；增量模式若 bulk=0 行，通常更适合报错/提示环境问题（可在后续扩展）。

## “文件应有数据”判定（避免误回退）

需要避免两类误判：

- 真正无数据行（只有表头）时，不应 fallback（否则 JDBC 也只会导入 0 行）
- 选错 sheet/空 sheet 时，仍应直接报错（现有 `ensureStandardCsv` 对 Excel 选 sheet 已有校验）

推荐判定策略（任一满足即可）：

- **CSV 字节阈值**：标准 CSV `Files.size(path)` 大于“仅表头”合理上限（例如 `> 256` 或 `> 512`，阈值以实际样本调优）
- **解析缓存行数**：若 `ParseResult` 记录数据行数明显大于 0（Excel/CSV parse 阶段已有 rowCount），且标准 CSV size 不异常

若上述均无法确认（例如 parseCache 缺失、文件极短），则保守按“可能无数据”处理：返回明确错误而不是盲目 fallback。

## TRUNCATE + fallback 的事务语义（选定为 A）

本项目的产品优先级选择为：

> **A：优先保证表不为空——允许在已 TRUNCATE 后自动 JDBC 补写**

因此事务语义定义为：

- **TRUNCATE 已执行且已提交/不可回滚**（MySQL `TRUNCATE` 会隐式提交，无法与后续写入形成“全原子”事务）
- 当 bulk stagingRows==0 且文件应有数据：
  - **不再依赖 staging merge**
  - 直接走 JDBC 插入路径写入目标表（使用与原 JDBC 实现一致的字段映射与冲突策略；TRUNCATE 模式下冲突策略视为 `ERROR` 或等价策略）
  - JDBC 写入完成后返回成功

该语义的产品解释是：覆盖导入时，我们确保“最终目标表有新数据”，即便 bulk 失败也不会留下空表。

## 失败与返回信息规范

当触发 fallback（TRUNCATE）时：

- 返回 `message` 建议包含：
  - `bulk load 写入 0 行，已自动切换为 JDBC 导入补写`
  - 诊断信息（尽量短）：标准 CSV 字节数、`SHOW WARNINGS`（若存在）

当不触发 fallback 且 stagingRows==0 时（例如判定无数据行）：

- 返回明确的业务失败：`文件未产生可导入的行（仅表头/空表）`，并附 CSV 字节数与 sheet 信息

## 可观测性（日志/heartbeat）

- Heartbeat 增加/复用 message：
  - bulk 路径：`INSERTING: LOAD DATA` → `INSERTING: MERGE`
  - fallback 路径：在 bulk 0 行判定后写入一次：`INSERTING: FALLBACK_JDBC`
- 关键日志字段建议：
  - requestId、databaseId、table、mode、stagingRows、standardCsvBytes、warnings(截断)

## 测试计划（最低集合）

- **集成测试（Testcontainers MySQL）**
  - 正常 bulk 成功：TRUNCATE 1 行 → 成功、行数正确
  - 人为制造 bulk=0 行且 CSV 非空：通过 mock/spy bulk load 方法让 stagingRows=0，验证会走 JDBC 写入且最终目标表非空

- **单元测试**
  - “文件应有数据”判定：覆盖 size 阈值边界、parseCache 缺失等路径

## 风险与后续

- TRUNCATE 的“不可回滚”是 MySQL 语义导致，fallback 只能保证“表不空”，无法保证“完全原子覆盖”。若未来需要真正原子覆盖，需要切换为 `DELETE FROM` 或 staging rename swap 等更复杂方案（不在本设计范围）。

