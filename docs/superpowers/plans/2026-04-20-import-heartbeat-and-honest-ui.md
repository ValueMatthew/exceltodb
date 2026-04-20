# Import Heartbeat + Honest Import UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fake looping circle progress on the “数据导入” step with a non-misleading “写入中” UI (elapsed timer + stage text) and add a backend heartbeat mechanism so the frontend can detect “still alive vs possibly stuck” during long imports. If heartbeat hasn't updated for 60s, the frontend shows a confirm dialog: continue waiting vs go back to retry.

**Architecture:** Add `requestId` to `/api/import` requests. The backend maintains an in-memory `ConcurrentHashMap<requestId, ImportHeartbeat>` updated during import stages (`TRUNCATE/READING/INSERTING/COMMITTING`). Expose `GET /api/import/heartbeat?requestId=...`. Frontend `ImportProgress.vue` starts a 5s poll to that endpoint while import request is pending, shows stage + elapsed time, and triggers a 60s no-update confirm prompt.

**Tech Stack:** Spring Boot 3 (Java 17), JUnit5 + MockMvc; Vue 3 + Element Plus + Axios.

---

## File map (create/modify)

**Backend**
- Modify: `server/src/main/java/com/exceltodb/model/ImportRequest.java`
- Create: `server/src/main/java/com/exceltodb/model/ImportHeartbeat.java`
- Create: `server/src/main/java/com/exceltodb/model/ImportStage.java`
- Create: `server/src/main/java/com/exceltodb/model/ImportStatus.java`
- Create: `server/src/main/java/com/exceltodb/service/ImportHeartbeatStore.java`
- Modify: `server/src/main/java/com/exceltodb/service/ImportService.java`
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerImportHeartbeatTest.java`

**Frontend**
- Modify: `client/src/components/ImportProgress.vue`
- (Optional) Modify: `client/src/App.vue` (only if we want to generate requestId higher up; prefer keeping it in ImportProgress)

---

### Task 1: Backend DTOs for heartbeat

**Files:**
- Create: `server/src/main/java/com/exceltodb/model/ImportHeartbeat.java`
- Create: `server/src/main/java/com/exceltodb/model/ImportStage.java`
- Create: `server/src/main/java/com/exceltodb/model/ImportStatus.java`

- [ ] **Step 1: Implement enums**

Create `ImportStatus.java`:

```java
package com.exceltodb.model;

public enum ImportStatus {
    RUNNING,
    SUCCESS,
    ERROR
}
```

Create `ImportStage.java`:

```java
package com.exceltodb.model;

public enum ImportStage {
    TRUNCATE,
    READING,
    INSERTING,
    COMMITTING
}
```

- [ ] **Step 2: Implement `ImportHeartbeat` DTO**

Create `ImportHeartbeat.java`:

```java
package com.exceltodb.model;

import lombok.Data;

@Data
public class ImportHeartbeat {
    private String requestId;
    private ImportStatus status;
    private ImportStage stage;
    private long updatedAt;      // epoch millis
    private long processedRows;  // rows processed (not necessarily committed)
    private String message;      // optional
}
```

- [ ] **Step 3: Compile server**

From `server/`:

`mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/model/ImportStatus.java server/src/main/java/com/exceltodb/model/ImportStage.java server/src/main/java/com/exceltodb/model/ImportHeartbeat.java
git commit -m "feat(server): add import heartbeat DTOs"
```

---

### Task 2: Add `requestId` to `ImportRequest`

**Files:**
- Modify: `server/src/main/java/com/exceltodb/model/ImportRequest.java`

- [ ] **Step 1: Update model**

Add field:

```java
private String requestId;
```

Lombok `@Data` will generate getters/setters automatically.

- [ ] **Step 2: Compile**

From `server/`:

`mvn -q -DskipTests compile`

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/exceltodb/model/ImportRequest.java
git commit -m "feat(server): accept requestId for import"
```

---

### Task 3: Heartbeat store (in-memory) with TTL cleanup

**Files:**
- Create: `server/src/main/java/com/exceltodb/service/ImportHeartbeatStore.java`

- [ ] **Step 1: Implement store**

Create `ImportHeartbeatStore.java`:

```java
package com.exceltodb.service;

import com.exceltodb.model.ImportHeartbeat;
import com.exceltodb.model.ImportStage;
import com.exceltodb.model.ImportStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImportHeartbeatStore {

    private static final long RETAIN_AFTER_DONE_MS = 10 * 60 * 1000L; // 10 minutes

    private final Map<String, ImportHeartbeat> store = new ConcurrentHashMap<>();
    private final Map<String, Long> doneAt = new ConcurrentHashMap<>();

    public ImportHeartbeat get(String requestId) {
        cleanupIfNeeded();
        return store.get(requestId);
    }

    public void start(String requestId) {
        ImportHeartbeat hb = new ImportHeartbeat();
        hb.setRequestId(requestId);
        hb.setStatus(ImportStatus.RUNNING);
        hb.setStage(ImportStage.READING);
        hb.setUpdatedAt(System.currentTimeMillis());
        hb.setProcessedRows(0);
        hb.setMessage("");
        store.put(requestId, hb);
        doneAt.remove(requestId);
    }

    public void update(String requestId, ImportStage stage, long processedRows, String message) {
        ImportHeartbeat hb = store.get(requestId);
        if (hb == null) {
            start(requestId);
            hb = store.get(requestId);
        }
        hb.setStage(stage);
        hb.setProcessedRows(processedRows);
        hb.setUpdatedAt(System.currentTimeMillis());
        if (message != null) hb.setMessage(message);
    }

    public void success(String requestId, long processedRows) {
        ImportHeartbeat hb = store.get(requestId);
        if (hb == null) {
            start(requestId);
            hb = store.get(requestId);
        }
        hb.setStatus(ImportStatus.SUCCESS);
        hb.setStage(ImportStage.COMMITTING);
        hb.setProcessedRows(processedRows);
        hb.setUpdatedAt(System.currentTimeMillis());
        doneAt.put(requestId, hb.getUpdatedAt());
    }

    public void error(String requestId, long processedRows, String message) {
        ImportHeartbeat hb = store.get(requestId);
        if (hb == null) {
            start(requestId);
            hb = store.get(requestId);
        }
        hb.setStatus(ImportStatus.ERROR);
        hb.setProcessedRows(processedRows);
        hb.setUpdatedAt(System.currentTimeMillis());
        hb.setMessage(message == null ? "" : message);
        doneAt.put(requestId, hb.getUpdatedAt());
    }

    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : doneAt.entrySet()) {
            if (now - e.getValue() > RETAIN_AFTER_DONE_MS) {
                String requestId = e.getKey();
                doneAt.remove(requestId);
                store.remove(requestId);
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

From `server/`:

`mvn -q -DskipTests compile`

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/ImportHeartbeatStore.java
git commit -m "feat(server): add import heartbeat store"
```

---

### Task 4: Wire heartbeat updates into `ImportService.importData`

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/ImportService.java`

- [ ] **Step 1: Inject `ImportHeartbeatStore`**

Add constructor param and field:

```java
private final ImportHeartbeatStore heartbeatStore;
```

Update constructor signature and assignment.

- [ ] **Step 2: Update heartbeat during stages**

In `importData`:
- At method start (after validating `requestId`): `heartbeatStore.start(requestId)`
- Before TRUNCATE: `heartbeatStore.update(requestId, ImportStage.TRUNCATE, 0, "")`
- Before reading/streaming rows: `heartbeatStore.update(requestId, ImportStage.READING, 0, "")`
- During inserting loop: periodically update stage `INSERTING` with `processedRows=rowIndex` (throttle to once per N rows, e.g. every 500 or every batch commit, to avoid too frequent updates)
- Before `conn.commit()`: `heartbeatStore.update(requestId, ImportStage.COMMITTING, processedRows, "")`
- On success: `heartbeatStore.success(requestId, importedRows)`
- On catch: `heartbeatStore.error(requestId, rowIndex, e.getMessage())`

Throttle snippet (example):

```java
if (requestId != null && (rowIndex % 500 == 0)) {
    heartbeatStore.update(requestId, ImportStage.INSERTING, rowIndex, "");
}
```

- [ ] **Step 3: Run server tests**

From `server/`:

`mvn -q test`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/ImportService.java
git commit -m "feat(server): update import heartbeat during import"
```

---

### Task 5: Add heartbeat endpoint `GET /api/import/heartbeat`

**Files:**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerImportHeartbeatTest.java`

- [ ] **Step 1: Write failing MockMvc test**

Create `ExcelControllerImportHeartbeatTest.java`:

```java
package com.exceltodb.controller;

import com.exceltodb.model.ImportHeartbeat;
import com.exceltodb.model.ImportStage;
import com.exceltodb.model.ImportStatus;
import com.exceltodb.service.DbService;
import com.exceltodb.service.ImportHeartbeatStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExcelController.class)
class ExcelControllerImportHeartbeatTest {

    @Autowired MockMvc mvc;

    @MockBean DbService dbService;
    @MockBean com.exceltodb.service.ExcelParserService excelParserService;
    @MockBean com.exceltodb.service.TableMatcherService tableMatcherService;
    @MockBean com.exceltodb.service.ImportService importService;
    @MockBean ImportHeartbeatStore heartbeatStore;

    @Test
    void heartbeat_returnsHeartbeatJson() throws Exception {
        ImportHeartbeat hb = new ImportHeartbeat();
        hb.setRequestId("rid-1");
        hb.setStatus(ImportStatus.RUNNING);
        hb.setStage(ImportStage.INSERTING);
        hb.setUpdatedAt(1713600000000L);
        hb.setProcessedRows(1234);
        hb.setMessage("");

        Mockito.when(heartbeatStore.get("rid-1")).thenReturn(hb);

        mvc.perform(get("/api/import/heartbeat").param("requestId", "rid-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("rid-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.stage").value("INSERTING"))
                .andExpect(jsonPath("$.processedRows").value(1234));
    }
}
```

- [ ] **Step 2: Implement endpoint**

In `ExcelController` inject `ImportHeartbeatStore` and add:

```java
@GetMapping("/import/heartbeat")
public ResponseEntity<ImportHeartbeat> getImportHeartbeat(@RequestParam String requestId) {
    try {
        ImportHeartbeat hb = heartbeatStore.get(requestId);
        if (hb == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(hb);
    } catch (Exception e) {
        return ResponseEntity.status(500).build();
    }
}
```

- [ ] **Step 3: Run tests**

From `server/`:

`mvn -q test -Dtest=ExcelControllerImportHeartbeatTest`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/controller/ExcelController.java server/src/test/java/com/exceltodb/controller/ExcelControllerImportHeartbeatTest.java
git commit -m "feat(server): add import heartbeat endpoint"
```

---

### Task 6: Frontend — honest importing UI + heartbeat polling

**Files:**
- Modify: `client/src/components/ImportProgress.vue`

- [ ] **Step 1: Generate requestId and attach to import request**

In `startImport`, before `axios.post('/api/import', ...)`:
- generate UUID (simple approach: `crypto.randomUUID()`; add fallback if not available)
- include `requestId` in POST body:

```js
const requestId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
const payload = { ...props.params, requestId }
```

- [ ] **Step 2: Replace fake looping pulse**

Remove `startPulse/stopPulse` logic and replace with:
- elapsed timer (`elapsedMs`, interval 1s)
- stage text (`stageText`) from heartbeat
- a stable non-percentage indicator:
  - keep `el-progress type=circle` but set a constant percentage (e.g. 25) and rotate via CSS animation on the circle container, OR use `el-icon` loading
  - key is: **no percentage shown**, no 10→90 loop.

- [ ] **Step 3: Heartbeat polling**

While status is `importing`:
- poll every 5s: `GET /api/import/heartbeat?requestId=...`
- store `lastUpdatedAt` from response
- if `Date.now() - lastUpdatedAt > 60_000`:
  - show `ElMessageBox.confirm("导入已超过60秒无进展...")`
  - “继续等待” → continue polling
  - “返回重试” → emit back and stop polling

- [ ] **Step 4: Stop polling/timers on success/error/unmount**

Ensure intervals cleared on:
- import success/error
- component unmount

- [ ] **Step 5: Build**

From `client/`:

`npm run build`

- [ ] **Step 6: Commit**

```bash
git add client/src/components/ImportProgress.vue
git commit -m "feat(client): add import heartbeat polling and honest UI"
```

---

### Task 7: End-to-end verification

**Files:** none

- [ ] **Step 1: Run server**

From `server/`:

`mvn spring-boot:run`

- [ ] **Step 2: Run client**

From `client/`:

`npm run dev`

- [ ] **Step 3: Verify behavior**

1) Start a large import (TRUNCATE mode) and confirm:
   - UI does not show fake percent looping
   - elapsed timer increments
   - stage updates at least once per minute
2) Temporarily simulate heartbeat stall (e.g. pause debugger / kill DB connection) and confirm:
   - after 60s, confirm dialog appears
   - choosing “返回重试” navigates back

---

## Plan self-review checklist (filled)

- **Spec coverage:** requestId, heartbeat endpoint, stage/elapsed UI, 60s no-update confirm all covered.
- **Placeholder scan:** no TBD/TODO; steps include concrete code/commands.
- **Type consistency:** `requestId` used consistently across request/polling/store.

