# Upload 临时文件（`uploads/`）TTL 清理策略设计（保留 1 小时）

## 背景与问题

当前后端上传文件会保存到 `app.upload.temp-path`（默认 `./uploads`），并由 `ExcelParserService.uploadedFiles` 在内存中维护 filename→Path 的映射用于后续 preview / import 流程。磁盘上的上传文件本身**不会在导入完成后删除**，也没有目录级的定期清理逻辑，因此会随着使用逐步积累，占用磁盘空间。

现有“清理”仅覆盖：

- `ExcelParserService.ensureStandardCsv()` 过程中生成的中间 `.tmp.*` 文件会 best-effort 删除
- `ImportHeartbeatStore` 仅清理内存心跳（完成后 10 分钟），不涉及磁盘文件

## 目标

- 对 `app.upload.temp-path` 目录下的临时上传文件实施 **TTL 清理（保留 1 小时）**
- **每 10 分钟**执行一次清理
- 避免误删仍在使用的文件（例如刚上传、正在 preview、正在导入）
- 清理失败不影响主流程（best-effort）

非目标：

- 不做按容量/数量上限的清理策略
- 不引入外部依赖（如 Redis/Quartz）
- 不改变上传文件命名规则与现有 API 行为

## 配置设计

在 `application.yml` 增加上传清理配置（默认开启）：

- `app.upload.cleanup.enabled`: `true|false`（默认 `true`）
- `app.upload.cleanup.ttl`: `Duration`（默认 `PT1H`）
- `app.upload.cleanup.interval`: `Duration`（默认 `PT10M`）

`ttl` 与 `interval` 使用 ISO-8601 `Duration` 字面量，便于运维调整。

## 方案概述（最终选择：定时任务扫描）

采用 Spring Scheduling 的定时任务：

- 在应用启动类开启调度：`@EnableScheduling`
- 新增 `UploadTempCleanupService`（或同等命名）：
  - `@Scheduled(fixedDelayString = "...")`（固定延迟，避免任务重叠；间隔由配置提供）
  - 每次执行扫描 `app.upload.temp-path` 目录中的文件，基于文件 `lastModifiedTime` 判断是否过期

选择理由：

- 能持续收敛磁盘占用，对用户无感
- 不把扫描 IO 放在请求链路上，避免影响接口延迟

## 清理规则与安全性

### 过期判定

- `expiredBefore = now - ttl`
- 当文件 `lastModifiedTime < expiredBefore` 时视为可清理候选

> 说明：使用 `lastModifiedTime` 作为近似“最后使用时间”。当文件被读取时，一般不会更新该时间戳；因此需要配合“活跃集合”来避免误删。

### 活跃文件跳过（避免误删）

清理时获取一份“活跃文件集合”，满足任一条件则跳过删除：

- 文件对应的 `filename` 仍存在于 `ExcelParserService` 内存映射 `uploadedFiles` 中（filename→Path）

实现方式：

- 为 `ExcelParserService` 增加只读访问方法（例如 `getActiveUploadPaths()` 或 `getActiveFilenames()`），返回当前映射的快照（避免并发遍历风险）
- `UploadTempCleanupService` 在一次清理周期内只使用快照，不与业务线程共享可变集合

### 扫描范围

- 默认仅扫描 `app.upload.temp-path` 目录**当前层级**文件（不递归）
- 可在实现时预留递归开关，但本次需求不强制

### 删除行为

- 删除采用 best-effort：单个文件删除失败仅记录 `warn`，不会抛出导致任务终止
- 任务整体异常捕获并记录日志，避免调度线程被意外终止

## 运行日志与可观测性

建议日志（INFO/WARN）：

- 每次任务：扫描文件数、候选数、实际删除数、耗时
- 删除失败：输出文件名（或相对路径）与异常简述

## 测试与验证

### 单元测试（建议）

- 使用临时目录创建若干文件：
  - 过期且不活跃 → 应删除
  - 过期但活跃（在 `uploadedFiles` 快照里）→ 不删除
  - 未过期 → 不删除
- 验证删除数量与剩余文件

### 手工验证（必做）

- 启动服务，将 `ttl` 调小到 `PT1M`、`interval` 调小到 `PT10S`（仅本地）
- 上传文件后等待超过 TTL，确认文件被清理；上传后立即 preview/import，确认文件不会被误删

## 兼容性与风险

- 启用 `@EnableScheduling` 对现有逻辑影响很小，但需要确保工程内无其他未预期的 `@Scheduled` 任务
- 若业务流程长时间持有同一个上传文件超过 TTL：
  - 由于“活跃集合跳过”机制，只要该文件仍在 `uploadedFiles` 中就不会被删除
  - 仍建议后续在导入完成时主动释放（从 `uploadedFiles` 移除）以加快清理

