## 背景与问题

当前“选择表”步骤仅支持系统基于 Excel 列名进行推荐匹配（阈值 90%）。但在实际业务场景中，业务人员往往明确知道目标表名；仍然需要等待系统跑完推荐结果，造成不必要的等待与阻塞。

本设计在不破坏原有“系统推荐表”流程的前提下，新增“手动指定目标表并校验”的入口，让用户可以更快进入导入设置，同时保持一致的安全阈值约束（匹配度必须 ≥ 90% 才允许继续）。

## 目标与非目标

### 目标

- 在“选择表”页面提供两种互斥模式：
  - **手动指定目标表**：用户输入/联想选择表名，点击“确认/校验”后由系统校验表存在性与匹配度（≥90）后才能继续。
  - **系统推荐表**：保留现有推荐列表与选择流程。
- **强约束**：手动模式校验结果若匹配度 < 90%，禁止继续，并提示用户可切换系统推荐表或重新输入。
- **性能体验**：进入页面默认不加载系统推荐；仅当切换到系统推荐模式时才请求推荐接口。
- **一致性**：手动校验与系统推荐使用相同的匹配算法与阈值（90），并复用后端现有匹配逻辑。

### 非目标

- 不做“低于阈值也允许继续”的绕过能力。
- 不做多表合并导入/多 sheet 同时导入等扩展。
- 不调整推荐算法本身（仍是列名匹配率，排除默认值/ON UPDATE 列）。

## 现状概览

- 前端页面：`client/src/components/TableRecommend.vue`
  - 当前：进入页面即请求 `/api/recommend`，展示推荐列表，选择后展开导入设置。
- 后端匹配算法：`server/src/main/java/com/exceltodb/service/TableMatcherService.java`
  - 输入：`TableInfo`（含 `excludedColumns`）+ Excel 列名
  - 输出：score（百分比）、matchedColumns 等
- 表元数据：`DbService.getAllTables(databaseId)` 可返回含 `excludedColumns` 的 `TableInfo` 列表；`DbService.getTableInfo(databaseId, tableName)` 当前仅返回 columns/PK，不含 excludedColumns（需补齐，见后文）。

## 方案（已确认）

采用“新增专用校验接口 + 轻量表名联想接口”的方案：

1. 新增 `GET /api/tables/{databaseId}/names`：仅返回表名列表，用于前端联想。
2. 新增 `POST /api/validate-table`：对指定表做存在性校验与匹配度计算，返回用于页面展示与导入设置的完整信息。

## 前端设计

### UI 与流程

在“选择表”页面新增模式切换（互斥）：

- **模式 A：手动指定目标表（默认）**
  - 表名输入框：支持输入 + 下拉联想（使用 `/api/tables/{db}/names` 的数据源）
  - 操作按钮：**“确认/校验”**
  - 校验通过后：展示校验信息并直接展开“导入设置”区域（与推荐模式一致）
  - 校验失败后：展示对应错误提示并保持在手动模式

- **模式 B：系统推荐表**
  - 复用现有推荐列表 UI 与“选择此表”按钮
  - **进入该模式时才请求** `/api/recommend`

### 状态与切换规则

- 切换模式时采用“保留各自上次结果”策略：
  - 从手动 → 推荐：保留手动的表名与校验结果；推荐模式若已加载过则直接展示上次推荐结果与选中项
  - 从推荐 → 手动：保留推荐列表/选中项；手动模式若已校验通过则直接展示上次校验通过的表信息与导入设置
  - 仅当上游关键输入发生变化（例如：更换数据库、重新上传文件、切换 sheet、预览列名变化）时，才需要清空两种模式的缓存状态（由上游步骤负责重置）

### 错误提示与禁用规则

手动模式点击“确认/校验”后的约束：

- **表不存在**：提示“未找到该表，请重新输入或切换系统推荐表”；禁止继续
- **匹配度 < 90%**：提示“匹配度 xx% < 90%，禁止继续；可切换系统推荐表或重新输入”；禁止继续
- **匹配度 ≥ 90%**：允许进入导入设置与下一步

### 与导入设置的衔接

一旦手动校验通过或选择了推荐表，前端进入与现有一致的导入设置交互：

- 导入模式：`INCREMENTAL` / `TRUNCATE`
- 冲突策略：在 `INCREMENTAL` 且表有主键时启用 `ERROR` / `UPDATE` / `IGNORE`
- 点击“开始导入”后向父组件传递 `selectedTable`，并由 `App.vue` 组装 `/api/import` 参数（含 `sheetIndex`）

## 后端 API 设计

### 1) GET `/api/tables/{databaseId}/names`

**返回**

```json
["orders","order_detail","users"]
```

**说明**

- 用于前端联想/搜索表名
- 默认一次性返回全部表名；如后续表数量较大，可扩展为支持 query 参数做服务端过滤（不在本期范围内）

### 2) POST `/api/validate-table`

**请求**

```json
{
  "databaseId": "prod_erp",
  "tableName": "orders",
  "filename": "orders.xlsx",
  "sheetIndex": 0,
  "columns": ["id", "order_no", "amount"]
}
```

**响应（建议形状）**

```json
{
  "exists": true,
  "threshold": 90,
  "score": 92,
  "reason": null,
  "table": {
    "name": "orders",
    "primaryKey": "id",
    "columnCount": 18,
    "columns": ["id", "order_no", "amount"],
    "matchedColumns": ["id", "order_no", "amount"]
  }
}
```

**失败场景**

- 表不存在：

```json
{ "exists": false, "threshold": 90, "score": 0, "reason": "NOT_FOUND", "table": null }
```

- 表存在但低于阈值（依然返回表信息便于展示/调试，但前端必须禁止继续）：

```json
{ "exists": true, "threshold": 90, "score": 75, "reason": "BELOW_THRESHOLD", "table": { "...": "..." } }
```

### 实现复用要求（关键）

- 匹配计算必须复用 `TableMatcherService`，保证与 `/api/recommend` 结果一致。
- `TableInfo.excludedColumns` 必须与 `/api/recommend` 一致（排除默认值/ON UPDATE 列），否则手动校验与系统推荐会出现分数不一致。
  - 当前 `DbService.getAllTables()` 会填充 `excludedColumns`
  - 当前 `DbService.getTableInfo(databaseId, tableName)` 未填充 `excludedColumns`，本期需要补齐同样的逻辑（或新增一个按表名获取完整 TableInfo 的方法）

## 兼容性与迁移

- 不改变 `/api/recommend` 的行为与响应结构
- 不改变 `/api/import` 的请求结构
- 仅对“选择表”页面交互增加模式与新的后端接口

## 验收标准

- 手动模式：
  - 能通过联想选择表名
  - 点击“确认/校验”后：
    - 表不存在：提示并禁止继续
    - 匹配度 < 90：提示并禁止继续
    - 匹配度 ≥ 90：展示表信息并展开导入设置，可进入导入步骤
- 推荐模式：
  - 切到推荐模式才请求 `/api/recommend`
  - 原推荐选择与导入设置流程不回归
- 切换模式：
  - 切换时保留各自上次结果，便于用户对比与快速返回
  - 上游关键输入变更时，两种模式的历史结果必须失效并重置，避免误用旧结果

