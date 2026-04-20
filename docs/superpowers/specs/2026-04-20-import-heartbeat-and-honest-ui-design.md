## 背景与问题

当前“数据导入”页面使用假的环形进度（10%→90%循环），在导入 1 万 / 7 万等中大批量数据时会出现“很快走完又重新开始”的误导，用户以为已导入完成，体验很差。

同时，导入采用单个大事务（TRUNCATE + 批量 INSERT + 最后 COMMIT）时，外部会话 `COUNT(*)` 在提交前可能一直是 0，用户无法判断导入是否仍在持续进行，或者任务是否已经挂起/卡死。

本设计目标是在**不做真实百分比进度**（不拆事务、不异步 job）前提下：

1) 前端“写入中”展示不误导（不再循环跑满）  
2) 提供一个“是否还活着”的**心跳**机制，让用户能判断导入是否持续推进，并在长时间无更新时给出明确的交互引导。

## 目标与非目标

### 目标

- 导入中不再展示伪百分比循环；改为稳定的“写入中”动画 + 文案 + 耗时计时。
- `/api/import` 请求新增 `requestId`（前端生成 UUID）用于关联本次导入。
- 后端在导入过程中维护一份“心跳”状态（阶段、最后更新时间、已处理行数等），并提供查询接口：
  - `GET /api/import/heartbeat?requestId=...`
- 前端每 5 秒轮询一次心跳：
  - 若 60 秒无心跳更新：弹出确认框（继续等待 / 返回重试）。

### 非目标

- 不实现“真实百分比进度”与精确 ETA。
- 不把导入改为异步 jobId 模式。
- 不改变 TRUNCATE 导入的大事务语义（保持原子性与可回滚性）。

## 设计概览

### 后端

#### 1) `/api/import` 参数扩展

在 `ImportRequest` 增加字段：

- `requestId: String`（必填，前端生成）

#### 2) 心跳状态存储（内存）

使用 `ConcurrentHashMap<String, ImportHeartbeat>` 存储每个 `requestId` 的心跳：

`ImportHeartbeat` 字段建议：

- `requestId`
- `status`: `RUNNING | SUCCESS | ERROR`
- `stage`: `TRUNCATE | READING | INSERTING | COMMITTING`
- `updatedAt`（毫秒时间戳）
- `processedRows`（已处理行数；不代表已提交，仅用于活跃信号）
- `message`（可选，错误或提示信息）

清理策略：

- 任务完成后保留 10 分钟再清理（避免前端刷新/短暂断网导致查不到）

#### 3) 新增接口：查询心跳

`GET /api/import/heartbeat?requestId=...`

响应示例：

```json
{
  "requestId": "9b7f...uuid",
  "status": "RUNNING",
  "stage": "INSERTING",
  "updatedAt": 1713600000000,
  "processedRows": 35000,
  "message": ""
}
```

异常处理：

- 与现有 controller 风格一致：异常返回 HTTP 500。
- requestId 不存在时：允许返回 404（更明确）或 200 空对象（需实现时统一选择并在前端兼容）。

### 前端

#### 1) 不误导的“写入中”展示

改造 `ImportProgress.vue`：

- 不显示百分比，也不再做 10%→90% 循环
- 展示稳定的“写入中”视觉提示（例如固定圆环 + 旋转动画 / 或 loading 风格）
- 展示耗时计时：`已运行 00:15:23`
- 展示阶段文案：`当前阶段：读取文件 / 写入中 / 提交中...`（来自心跳 `stage`）

#### 2) 心跳轮询与无更新提示

- 前端生成 `requestId`（UUID）并随 `/api/import` 一并提交
- 导入页每 5 秒调用 `GET /api/import/heartbeat?requestId=...`
- 若 `now - updatedAt > 60 秒`：
  - 弹 `ElMessageBox.confirm`
    - **继续等待**：继续轮询
    - **返回重试**：触发 `back` 返回上一步
- 若心跳 `status=ERROR`：
  - 直接进入失败态并展示 message
- 当 `/api/import` 返回 success/error：
  - 停止轮询并进入对应结果页

## 验收标准

- [ ] 导入中环形图不再“跑满又重来”，不出现误导性的百分比进度
- [ ] 导入中显示耗时计时，并根据心跳显示阶段文案
- [ ] 前端能每 5 秒获取一次心跳并更新“最后活跃/已处理行数”
- [ ] 连续 60 秒无心跳更新时弹出确认框（继续等待 / 返回重试）
- [ ] 保持 TRUNCATE 大事务语义不变（原子性不变）

