# Manual Table Validation + Lazy Recommendations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a “manual target table validate” mode to the “选择表” step (with table-name autocomplete and server-side validation) while keeping the existing “系统推荐表” mode and only loading recommendations when that mode is entered; switching modes preserves each mode’s previous results.

**Architecture:** Frontend `TableRecommend.vue` becomes a 2-mode UI (manual vs recommend) with per-mode cached state. Backend adds two endpoints: `GET /api/tables/{databaseId}/names` (autocomplete data) and `POST /api/validate-table` (table existence + score/threshold results), reusing existing `DbService` metadata access and `TableMatcherService` scoring.

**Tech Stack:** Vue 3 + Element Plus + Axios (client), Spring Boot 3 (Java 17), JUnit 5 + Spring Boot Test + MockMvc (server).

---

## File map (create/modify)

**Backend**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Modify: `server/src/main/java/com/exceltodb/service/DbService.java`
- Create: `server/src/main/java/com/exceltodb/model/ValidateTableRequest.java`
- Create: `server/src/main/java/com/exceltodb/model/ValidateTableResponse.java`
- Create: `server/src/main/java/com/exceltodb/model/ValidateTableReason.java`
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerValidateTableTest.java`

**Frontend**
- Modify: `client/src/components/TableRecommend.vue`

---

### Task 1: Backend models for validate-table

**Files:**
- Create: `server/src/main/java/com/exceltodb/model/ValidateTableRequest.java`
- Create: `server/src/main/java/com/exceltodb/model/ValidateTableResponse.java`
- Create: `server/src/main/java/com/exceltodb/model/ValidateTableReason.java`

- [ ] **Step 1: Write the failing test (compile-level)**

No test yet; this task will be validated by compilation in Step 2.

- [ ] **Step 2: Implement model classes**

Create `ValidateTableRequest.java`:

```java
package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class ValidateTableRequest {
    private String databaseId;
    private String tableName;
    private String filename;
    private Integer sheetIndex;
    private List<String> columns;
}
```

Create `ValidateTableReason.java`:

```java
package com.exceltodb.model;

public enum ValidateTableReason {
    NOT_FOUND,
    BELOW_THRESHOLD
}
```

Create `ValidateTableResponse.java`:

```java
package com.exceltodb.model;

import lombok.Data;

@Data
public class ValidateTableResponse {
    private boolean exists;
    private int threshold;
    private int score;
    private ValidateTableReason reason; // null when pass
    private TableRecommendation table;  // reuse existing payload shape used by frontend
}
```

- [ ] **Step 3: Compile server to verify**

Run (from `server/`):

`mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/model/ValidateTableRequest.java server/src/main/java/com/exceltodb/model/ValidateTableResponse.java server/src/main/java/com/exceltodb/model/ValidateTableReason.java
git commit -m "feat(server): add validate-table request/response models"
```

---

### Task 2: DbService — table names + excludedColumns for single-table metadata

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/DbService.java`

- [ ] **Step 1: Write the failing test (controller tests will cover behavior)**

Skip direct DbService test unless you already have a stable test database configured. Controller tests (Task 3/4) will mock DbService; final verification will be manual against a real DB.

- [ ] **Step 2: Implement `getAllTableNames(databaseId)`**

Add method:

```java
public List<String> getAllTableNames(String databaseId) {
    List<String> names = new ArrayList<>();
    try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection()) {
        DatabaseMetaData metaData = conn.getMetaData();
        String catalog = conn.getCatalog();
        String[] types = {"TABLE"};
        try (ResultSet rs = metaData.getTables(catalog, null, "%", types)) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
    } catch (SQLException e) {
        throw new RuntimeException("获取表名列表失败: " + e.getMessage(), e);
    }
    return names;
}
```

- [ ] **Step 3: Update `getTableInfo(databaseId, tableName)` to populate `excludedColumns`**

Copy the same excludedColumns SQL logic from `getAllTables()` into `getTableInfo()` and set it on `TableInfo`:

```java
List<String> excludedColumns = new ArrayList<>();
String schemaName = conn.getCatalog();
String excludedColsSql = "SELECT COLUMN_NAME, COLUMN_DEFAULT, LOWER(EXTRA) as EXTRA_LOWER FROM INFORMATION_SCHEMA.COLUMNS " +
        "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
        "AND (COLUMN_DEFAULT IS NOT NULL AND COLUMN_DEFAULT != '' " +
        "     OR LOWER(EXTRA) LIKE '%on update%')";
try (PreparedStatement pstmt = conn.prepareStatement(excludedColsSql)) {
    pstmt.setString(1, schemaName);
    pstmt.setString(2, tableName);
    try (ResultSet exRs = pstmt.executeQuery()) {
        while (exRs.next()) {
            String colName = exRs.getString("COLUMN_NAME");
            String defaultVal = exRs.getString("COLUMN_DEFAULT");
            String extraLower = exRs.getString("EXTRA_LOWER");
            boolean hasDefault = defaultVal != null && !defaultVal.trim().isEmpty();
            boolean hasOnUpdate = extraLower != null && extraLower.contains("on update");
            if ((hasDefault || hasOnUpdate) && !excludedColumns.contains(colName)) {
                excludedColumns.add(colName);
            }
        }
    }
}
tableInfo.setExcludedColumns(excludedColumns);
```

- [ ] **Step 4: Compile server**

From `server/`:

`mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/DbService.java
git commit -m "feat(server): add table names and excluded columns in getTableInfo"
```

---

### Task 3: Endpoint — GET `/api/tables/{databaseId}/names`

**Files:**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerValidateTableTest.java`

- [ ] **Step 1: Write failing MockMvc test**

Create `ExcelControllerValidateTableTest.java`:

```java
package com.exceltodb.controller;

import com.exceltodb.service.DbService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExcelController.class)
class ExcelControllerValidateTableTest {

    @Autowired MockMvc mvc;

    @MockBean DbService dbService;
    @MockBean com.exceltodb.service.ExcelParserService excelParserService;
    @MockBean com.exceltodb.service.TableMatcherService tableMatcherService;
    @MockBean com.exceltodb.service.ImportService importService;

    @Test
    void getTableNames_returnsJsonArray() throws Exception {
        Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("orders", "users"));

        mvc.perform(get("/api/tables/prod_erp/names"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"orders\",\"users\"]"));
    }
}
```

- [ ] **Step 2: Implement endpoint**

In `ExcelController.java`, add:

```java
@GetMapping("/tables/{databaseId}/names")
public ResponseEntity<List<String>> getTableNames(@PathVariable String databaseId) {
    try {
        return ResponseEntity.ok(dbService.getAllTableNames(databaseId));
    } catch (Exception e) {
        return ResponseEntity.status(500).build();
    }
}
```

- [ ] **Step 3: Run test**

From `server/`:

`mvn -q test -Dtest=ExcelControllerValidateTableTest#getTableNames_returnsJsonArray`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/controller/ExcelController.java server/src/test/java/com/exceltodb/controller/ExcelControllerValidateTableTest.java
git commit -m "feat(server): add table names endpoint for autocomplete"
```

---

### Task 4: Endpoint — POST `/api/validate-table`

**Files:**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Modify (maybe): `server/src/main/java/com/exceltodb/service/ExcelParserService.java` (only if needed; prefer existing preview fallback)
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerValidateTableTest.java`

- [ ] **Step 1: Add failing tests for validate-table scenarios**

Append imports:

```java
import com.exceltodb.model.TableInfo;
import com.exceltodb.model.TableRecommendation;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

Add tests:

```java
@Test
void validateTable_notFound_returnsExistsFalse() throws Exception {
    Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("users"));

    String body = "{\"databaseId\":\"prod_erp\",\"tableName\":\"orders\",\"sheetIndex\":0,\"columns\":[\"id\"]}";

    mvc.perform(post("/api/validate-table")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exists").value(false))
        .andExpect(jsonPath("$.reason").value("NOT_FOUND"))
        .andExpect(jsonPath("$.threshold").value(90))
        .andExpect(jsonPath("$.score").value(0));
}

@Test
void validateTable_belowThreshold_returnsReasonBelowThreshold() throws Exception {
    TableInfo info = new TableInfo();
    info.setName("orders");
    info.setColumns(List.of("id", "order_no"));
    info.setExcludedColumns(List.of());
    info.setColumnCount(2);
    info.setPrimaryKey("id");

    Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("orders"));
    Mockito.when(dbService.getTableInfo("prod_erp", "orders")).thenReturn(info);

    TableRecommendation rec = new TableRecommendation();
    rec.setTableName("orders");
    rec.setScore(50);
    rec.setColumnCount(2);
    rec.setPrimaryKey("id");
    rec.setColumns(info.getColumns());
    rec.setMatchedColumns(List.of("id"));

    Mockito.when(tableMatcherService.findBestMatch(Mockito.anyList(), Mockito.anyList(), Mockito.anyString()))
            .thenReturn(rec);

    String body = "{\"databaseId\":\"prod_erp\",\"tableName\":\"orders\",\"sheetIndex\":0,\"columns\":[\"id\",\"x_not_match\"]}";

    mvc.perform(post("/api/validate-table")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exists").value(true))
        .andExpect(jsonPath("$.reason").value("BELOW_THRESHOLD"))
        .andExpect(jsonPath("$.threshold").value(90))
        .andExpect(jsonPath("$.score").value(50))
        .andExpect(jsonPath("$.table.tableName").value("orders"));
}

@Test
void validateTable_pass_returnsNullReasonAndTable() throws Exception {
    TableInfo info = new TableInfo();
    info.setName("orders");
    info.setColumns(List.of("id", "order_no"));
    info.setExcludedColumns(List.of());
    info.setColumnCount(2);
    info.setPrimaryKey("id");

    Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("orders"));
    Mockito.when(dbService.getTableInfo("prod_erp", "orders")).thenReturn(info);

    TableRecommendation rec = new TableRecommendation();
    rec.setTableName("orders");
    rec.setScore(95);
    rec.setColumnCount(2);
    rec.setPrimaryKey("id");
    rec.setColumns(info.getColumns());
    rec.setMatchedColumns(List.of("id", "order_no"));

    Mockito.when(tableMatcherService.findBestMatch(Mockito.anyList(), Mockito.anyList(), Mockito.anyString()))
            .thenReturn(rec);

    String body = "{\"databaseId\":\"prod_erp\",\"tableName\":\"orders\",\"sheetIndex\":0,\"columns\":[\"id\",\"order_no\"]}";

    mvc.perform(post("/api/validate-table")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exists").value(true))
        .andExpect(jsonPath("$.threshold").value(90))
        .andExpect(jsonPath("$.score").value(95))
        .andExpect(jsonPath("$.reason").doesNotExist())
        .andExpect(jsonPath("$.table.tableName").value("orders"));
}
```

If your JSON serializer includes `"reason": null`, replace `.doesNotExist()` with `.isEmpty()` or `.value(org.hamcrest.Matchers.nullValue())`.

- [ ] **Step 2: Implement controller logic**

In `ExcelController.java`, add:

```java
@PostMapping("/validate-table")
public ResponseEntity<ValidateTableResponse> validateTable(@RequestBody ValidateTableRequest request) {
    try {
        final int threshold = 90;

        String dbId = request.getDatabaseId();
        String tableName = request.getTableName();

        ValidateTableResponse resp = new ValidateTableResponse();
        resp.setThreshold(threshold);

        // existence check via table names
        List<String> names = dbService.getAllTableNames(dbId);
        boolean exists = names.stream().anyMatch(n -> n.equalsIgnoreCase(tableName));
        resp.setExists(exists);

        if (!exists) {
            resp.setScore(0);
            resp.setReason(ValidateTableReason.NOT_FOUND);
            resp.setTable(null);
            return ResponseEntity.ok(resp);
        }

        // columns: prefer client-provided
        List<String> excelColumns = request.getColumns();
        if (excelColumns == null || excelColumns.isEmpty()) {
            int sheetIndex = request.getSheetIndex() != null ? request.getSheetIndex() : 0;
            PreviewResult preview = excelParserService.getPreview(request.getFilename(), 100, sheetIndex);
            excelColumns = preview.getColumns();
        }

        // compute score using same matcher service
        TableInfo tableInfo = dbService.getTableInfo(dbId, tableName);
        TableRecommendation rec = tableMatcherService.findBestMatch(List.of(tableInfo), excelColumns, request.getFilename());
        int score = rec == null ? 0 : rec.getScore();
        resp.setScore(score);
        resp.setTable(rec);

        if (score < threshold) {
            resp.setReason(ValidateTableReason.BELOW_THRESHOLD);
        } else {
            resp.setReason(null);
        }

        return ResponseEntity.ok(resp);
    } catch (Exception e) {
        return ResponseEntity.status(500).build();
    }
}
```

- [ ] **Step 3: Run controller tests**

From `server/`:

`mvn -q test -Dtest=ExcelControllerValidateTableTest`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/controller/ExcelController.java server/src/test/java/com/exceltodb/controller/ExcelControllerValidateTableTest.java
git commit -m "feat(server): add validate-table endpoint for manual target table"
```

---

### Task 5: Frontend — TableRecommend.vue two-mode UI + state preservation

**Files:**
- Modify: `client/src/components/TableRecommend.vue`

- [ ] **Step 1: Add new state variables and mode enum**

Add:
- `mode = ref('MANUAL' | 'RECOMMEND')` (default `'MANUAL'`)
- Manual cached state:
  - `manualTableName`, `manualOptions` (table name list), `manualLoadingNames`, `manualValidating`, `manualValidationResult` (response), `manualError`
- Recommend cached state:
  - reuse existing `recommendations`, `topScore`, `selectedTableName`, `loading` but only populate when in RECOMMEND
  - a boolean `recommendLoaded` to avoid re-fetching if already loaded

- [ ] **Step 2: Implement table names fetch + local filtering**

On first entry into MANUAL mode (or onMounted), fetch:
- `GET /api/tables/{selectedDb.id}/names`
- store full list in memory, use Element Plus `filterable` to filter client-side

If you prefer remote filtering, keep a single list fetch to stay YAGNI.

- [ ] **Step 3: Implement validate button behavior**

On “确认/校验”:
- call `POST /api/validate-table` with:
  - `databaseId: selectedDb.value.id`
  - `tableName: manualTableName.value`
  - `filename: uploadedFile.value?.filename`
  - `sheetIndex: uploadedFile.value?.sheetIndex ?? 0`
  - `columns: previewData.value?.columns || []`
- handle outcomes:
  - `exists=false` → show error, keep `selectedTableInfo` null
  - `exists=true && score<threshold` → show error, keep `selectedTableInfo` null
  - pass → set `selectedTableInfo` from returned `table` and set `selectedTable.value` via existing `selectTable()` path so import options expand

- [ ] **Step 4: Make recommendations lazy**

Remove the unconditional `onMounted(loadRecommendation)`; instead:
- if user switches mode to `RECOMMEND`:
  - if not `recommendLoaded`, call existing `loadRecommendation()`

- [ ] **Step 5: Preserve per-mode state on switching**

Implement switching such that:
- switching MANUAL → RECOMMEND does NOT clear manual variables
- switching RECOMMEND → MANUAL does NOT clear recommend variables

Also ensure `selectedTableInfo` reflects the currently active mode’s selected/validated table:
- If switching to MANUAL and manual last validation passed, show that `selectedTableInfo`
- If switching to RECOMMEND and user previously selected a recommended table, show that `selectedTableInfo`

Practical approach:
- store `manualSelectedTableInfo` and `recommendSelectedTableInfo` separately
- store `manualImportMode/conflictStrategy` and `recommendImportMode/conflictStrategy` separately if you want per-mode persistence; otherwise keep one shared (simpler) — pick one and be consistent

- [ ] **Step 6: Manual test**

Run client:
- From `client/`: `npm run dev`

Manual checks:
- Default mode MANUAL shows autocomplete + validate button, does not show “正在分析匹配度” until recommendation mode entered
- Validate success expands import options and can proceed
- Validate failure blocks and shows message; “开始导入” is not reachable without valid selection
- Switch to RECOMMEND triggers `/api/recommend` once; switching back keeps both sides’ last results

- [ ] **Step 7: Commit**

```bash
git add client/src/components/TableRecommend.vue
git commit -m "feat(client): add manual table validate mode and lazy recommendations"
```

---

### Task 6: End-to-end verification (manual)

**Files:** none

- [ ] **Step 1: Start server**

From `server/`:

`mvn spring-boot:run`

Expected: server running on port 8080

- [ ] **Step 2: Start client**

From `client/`:

`npm run dev`

Expected: Vite dev server on port 3000

- [ ] **Step 3: Full flow**

1) Select database → upload excel/csv → preview columns
2) In “选择表”:
   - MANUAL: type known table name, validate, ensure score shown and import options appear
   - try invalid table name, see NOT_FOUND prompt
   - try a table with low score, see BELOW_THRESHOLD prompt and no ability to proceed
   - switch to RECOMMEND and ensure it loads and remains available

---

## Plan self-review checklist (filled)

- **Spec coverage:** manual vs recommend modes; lazy recommend load; per-mode state preserved; new endpoints + excludedColumns consistency covered in Tasks 2–5.
- **Placeholder scan:** no TBD/TODO; every step includes concrete code/commands.
- **Type consistency:** endpoint paths `/api/tables/{db}/names` and `/api/validate-table` used consistently; response uses existing `TableRecommendation` as `table`.

