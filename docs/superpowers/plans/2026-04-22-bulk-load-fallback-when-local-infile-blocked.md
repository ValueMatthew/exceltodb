# Bulk Load Fallback When LOCAL INFILE Is Blocked Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When MySQL blocks `LOAD DATA LOCAL INFILE` (observed as SQL error `1148` / “not allowed with this MySQL version”) or bulk-load silently loads **0 rows** while the uploaded file clearly contains data rows, automatically **fall back to the existing JDBC batch import path** and never return misleading `success=true` with `importedRows=0`.

**Architecture:** Keep `BulkLoadImportService` as the fast path when supported, but make `ImportService` the orchestrator: after bulk import, validate “expected rows vs actual imported rows” using existing `ParseResult.rowCount` (already computed at upload time for CSV). If bulk path is ineffective/unsupported, rerun `importData` logic via the legacy JDBC path (same transaction semantics as today for JDBC). Separately harden `BulkLoadImportService` so `staging COUNT(*)==0` cannot be classified as success for non-empty imports.

**Tech Stack:** Spring Boot 3, Java 17, MySQL Connector/J 8.2, HikariCP, JUnit 5 + Mockito.

---

## File structure (what changes where)

**Modify**
- `server/src/main/java/com/exceltodb/config/DataSourceConfig.java`
  - Add JDBC URL flags to speed up JDBC fallback batching (`rewriteBatchedStatements=true`, etc.).
- `server/src/main/java/com/exceltodb/service/ImportService.java`
  - Orchestrate: try bulk → validate → fallback JDBC.
  - Extract existing JDBC import body into a private method to avoid duplication while keeping behavior identical.
- `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`
  - After `LOAD DATA` + `COUNT(*)`, if `processedRows==0`, set `success=false` with an actionable message (still return `ImportResult`, do not throw unless necessary).

**Tests**
- `server/src/test/java/com/exceltodb/service/ImportServiceRoutingTest.java`
  - Update expectations for bulk-enabled path when bulk returns “successful but empty”.
- **Create** `server/src/test/java/com/exceltodb/service/ImportServiceBulkLoadFallbackTest.java`
  - New focused tests for fallback behavior.

---

### Task 1: Add JDBC URL tuning for fallback performance

**Files:**
- Modify: `server/src/main/java/com/exceltodb/config/DataSourceConfig.java`

- [ ] **Step 1: Update JDBC URL**

Append (comma-separated query params):

- `rewriteBatchedStatements=true`

Keep existing:
- `useSSL=false`
- `allowPublicKeyRetrieval=true`
- `serverTimezone=Asia/Shanghai`
- `allowLoadLocalInfile=true` (still needed for environments that support LOCAL INFILE)

Example target URL shape:

```java
config.setJdbcUrl(String.format(
  "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowLoadLocalInfile=true&rewriteBatchedStatements=true",
  dbInfo.getHost(), dbInfo.getPort(), dbInfo.getDatabase()
));
```

- [ ] **Step 2: Compile check**

Run:

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q -DskipTests package
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/config/DataSourceConfig.java
git commit -m "perf(server): speed up jdbc fallback with rewriteBatchedStatements"
```

---

### Task 2: Make bulk-load “0 rows” a hard failure (non-empty files)

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`

- [ ] **Step 1: Add explicit guard after staging count**

Right after:

```java
processedRows = countRows(conn, tmpTable);
```

If `processedRows <= 0`, then:
- `throw new RuntimeException("Bulk load 未写入任何行：当前数据库可能禁用 LOAD DATA LOCAL INFILE（常见错误 1148），或 CSV 未解析出数据行。请改用 JDBC 导入或联系 DBA 开启 LOCAL INFILE。");`

This ensures the outer catch path returns `success=false` **unless** we later implement orchestrator fallback (Task 3) which will catch and continue.

> Note: For truly empty files (`ParseResult.rowCount==0`), orchestrator should not call bulk or should treat as a separate user-visible outcome; Task 3 uses `ParseResult` to distinguish.

- [ ] **Step 2: Run unit tests**

Run:

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/service/BulkLoadImportService.java
git commit -m "fix(server): fail bulk load when staging remains empty"
```

---

### Task 3: Orchestrate automatic fallback in `ImportService`

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/ImportService.java`

#### Design details (must implement exactly)

1) **Expected row count source**
- `ParseResult pr = excelParserService.getParseResult(request.getFilename());`
- If `pr == null`: treat as unknown → **do not** auto-fallback solely based on `importedRows==0` (avoid false positives).
- If `pr != null`: `expectedDataRows = pr.getRowCount()` (this is already “exclude header” count for CSV in `ExcelParserService.parseCsv`).

2) **Bulk attempt**
If `appConfig.isBulkLoadEnabled()`:
- `ImportResult bulk = bulkLoadImportService.importWithLoadData(ds, request);`

3) **Fallback decision (minimal, deterministic)**

If **any** of the following is true, run JDBC import (`importDataJdbc(...)`) and return that result:

- `bulk.isSuccess()==false` OR message contains `1148` OR message contains `LOCAL INFILE` OR message contains `Bulk load 未写入任何行` (string match is acceptable here; keep it narrow)
- OR (`bulk.isSuccess()==true` AND `bulk.getImportedRows()==0` AND `pr != null` AND `pr.getRowCount() > 0`)

4) **JDBC import extraction**
- Move the existing bulk-disabled JDBC code in `importData` into:

```java
private ImportResult importDataJdbc(DataSource ds, ImportRequest request) throws IOException, InvalidFormatException
```

- `importData(...)` becomes orchestration + delegation.

5) **Heartbeat**
- Keep existing `heartbeatStore.start(requestId)` at beginning of `importData`.
- Do **not** double-send success/error heartbeats across bulk+fallback; JDBC path already ends with success/error.

- [ ] **Step 1: Write failing test first**

Create `server/src/test/java/com/exceltodb/service/ImportServiceBulkLoadFallbackTest.java`:

```java
package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ParseResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

public class ImportServiceBulkLoadFallbackTest {

    @Test
    void bulkLoad_success_but_zero_rows_with_nonempty_file_falls_back_to_jdbc() throws Exception {
        DataSourceConfig dataSourceConfig = mock(DataSourceConfig.class);
        ExcelParserService excelParserService = mock(ExcelParserService.class);
        DbService dbService = mock(DbService.class);
        AppConfig appConfig = mock(AppConfig.class);
        ImportHeartbeatStore heartbeatStore = mock(ImportHeartbeatStore.class);
        BulkLoadImportService bulk = mock(BulkLoadImportService.class);

        DataSource ds = mock(DataSource.class);
        when(dataSourceConfig.getDataSource("db1")).thenReturn(ds);
        when(appConfig.isBulkLoadEnabled()).thenReturn(true);

        ParseResult pr = new ParseResult();
        pr.setRowCount(70000);
        when(excelParserService.getParseResult("f.csv")).thenReturn(pr);

        ImportResult bulkRes = new ImportResult();
        bulkRes.setSuccess(true);
        bulkRes.setImportedRows(0);
        bulkRes.setMessage("导入成功");
        when(bulk.importWithLoadData(same(ds), any(ImportRequest.class))).thenReturn(bulkRes);

        // Force JDBC path to throw quickly to prove it was attempted AFTER bulk.
        when(ds.getConnection()).thenThrow(new RuntimeException("jdbc-called"));

        ImportRequest req = new ImportRequest();
        req.setDatabaseId("db1");
        req.setFilename("f.csv");
        req.setTableName("t");
        req.setImportMode("INCREMENTAL");
        req.setConflictStrategy("ERROR");
        req.setRequestId("rid");

        ImportService svc = new ImportService(dataSourceConfig, excelParserService, dbService, appConfig, heartbeatStore, bulk);
        ImportResult out = svc.importData(req);

        // Orchestrator should attempt JDBC after bulk returned misleading success.
        // Exact success/failure of JDBC is not the focus of this test; connection acquisition proves fallback path ran.
        verify(bulk, times(1)).importWithLoadData(same(ds), same(req));
        verify(ds, atLeastOnce()).getConnection();
    }
}
```

Run:

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q -Dtest=ImportServiceBulkLoadFallbackTest test
```

Expected: **FAIL** (because orchestration not implemented yet)

- [ ] **Step 2: Implement orchestration + extraction**

Implement Task 3 design in `ImportService.java`.

- [ ] **Step 3: Update `ImportServiceRoutingTest`**

Update `bulkLoadEnabled_routesToBulkLoadService` test: if it only expects one bulk call, keep it, but add a new test file for fallback rather than overloading routing test.

- [ ] **Step 4: Run full tests**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/service/ImportService.java server/src/test/java/com/exceltodb/service/ImportServiceBulkLoadFallbackTest.java server/src/test/java/com/exceltodb/service/ImportServiceRoutingTest.java
git commit -m "fix(server): fallback to jdbc import when bulk local infile is ineffective"
```

---

### Task 4 (optional but recommended): Immediate heartbeat poll once (UX)

**Files:**
- Modify: `client/src/components/ImportProgress.vue`

- [ ] **Step 1: After `startHeartbeatPoll()`, immediately `await pollHeartbeat()` once**

This prevents “import finished <5s so no heartbeat requests exist” confusion.

- [ ] **Step 2: `npm run build`**

```powershell
Set-Location D:\CodeProject\exceltodb\client
npm run build
```

- [ ] **Step 3: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add client/src/components/ImportProgress.vue
git commit -m "fix(client): poll import heartbeat immediately"
```

---

## Plan self-review

**Spec coverage**
- Detect unsupported LOCAL INFILE / ineffective bulk load: Task 2 + Task 3
- Preserve import functionality without server filesystem access: Task 3 (JDBC fallback)
- Improve JDBC performance when fallback happens: Task 1
- Reduce misleading UI/Network confusion: Task 4

**Placeholder scan**
- No TBD sections.

**Type consistency**
- Uses existing `ParseResult.rowCount` semantics from upload-time parsing.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-22-bulk-load-fallback-when-local-infile-blocked.md`.

Two execution options:

**1. Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
