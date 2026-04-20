# Table Preview Modal (Columns + 5 Rows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user has selected a target table with match score ≥ 90% (manual or recommend), show a button “预览目标表(字段+5行)” in the import settings card. Clicking it opens a modal that displays the table name + score, all columns, and up to 5 real rows from the target MySQL table (no ordering; `LIMIT 5`). If 0 rows, still show columns and display “该表暂无数据”.

**Architecture:** Add a read-only backend endpoint `GET /api/table-preview` that returns `{ tableName, columns, rows }` (max 5 rows, limit hard-capped at 5). On the frontend, extend `TableRecommend.vue` to render the preview button conditionally (score ≥ 90 and table selected) and to open an `el-dialog` which fetches preview data and displays it in a small table. Score displayed in modal is taken from existing UI state (no server recompute).

**Tech Stack:** Spring Boot 3 (Java 17), JDBC, JUnit5 + MockMvc; Vue 3 + Element Plus + Axios.

---

## File map (create/modify)

**Backend**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Create: `server/src/main/java/com/exceltodb/model/TablePreviewResponse.java`
- Modify (optional): `server/src/main/java/com/exceltodb/service/DbService.java` (add method to fetch preview data cleanly)
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerTablePreviewTest.java`

**Frontend**
- Modify: `client/src/components/TableRecommend.vue`

---

### Task 1: Backend response model for table preview

**Files:**
- Create: `server/src/main/java/com/exceltodb/model/TablePreviewResponse.java`

- [ ] **Step 1: Implement `TablePreviewResponse`**

Create `TablePreviewResponse.java`:

```java
package com.exceltodb.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TablePreviewResponse {
    private String tableName;
    private List<String> columns;
    private List<Map<String, Object>> rows;
}
```

- [ ] **Step 2: Compile server**

From `server/`:

`mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/exceltodb/model/TablePreviewResponse.java
git commit -m "feat(server): add table preview response model"
```

---

### Task 2: Backend service helper to fetch `SELECT * LIMIT 5` safely

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/DbService.java`

- [ ] **Step 1: Add helper method `getTablePreview(databaseId, tableName, limit)`**

Add to `DbService`:

```java
public com.exceltodb.model.TablePreviewResponse getTablePreview(String databaseId, String tableName, int limit) {
    int capped = Math.min(5, Math.max(1, limit));
    var resp = new com.exceltodb.model.TablePreviewResponse();
    resp.setTableName(tableName);

    try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection();
         Statement stmt = conn.createStatement()) {

        // columns (all)
        List<String> columns = new ArrayList<>();
        try (ResultSet colRs = conn.getMetaData().getColumns(conn.getCatalog(), null, tableName, null)) {
            while (colRs.next()) {
                columns.add(colRs.getString("COLUMN_NAME"));
            }
        }
        resp.setColumns(columns);

        // rows (up to 5, no ordering)
        String sql = "SELECT * FROM `" + tableName.replace("`", "``") + "` LIMIT " + capped;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    String col = md.getColumnLabel(i);
                    row.put(col, rs.getObject(i));
                }
                rows.add(row);
            }
        }
        resp.setRows(rows);
        return resp;
    } catch (SQLException e) {
        throw new RuntimeException("预览目标表失败: " + e.getMessage(), e);
    }
}
```

Notes:
- We cannot bind table identifiers in JDBC placeholders; we quote with backticks and escape backticks to reduce injection risk.
- Limit is hard-capped at 5 per spec.

- [ ] **Step 2: Compile server**

From `server/`:

`mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/DbService.java
git commit -m "feat(server): add db table preview helper"
```

---

### Task 3: Backend endpoint `GET /api/table-preview` + MockMvc tests

**Files:**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Test: `server/src/test/java/com/exceltodb/controller/ExcelControllerTablePreviewTest.java`

- [ ] **Step 1: Write failing controller test**

Create `ExcelControllerTablePreviewTest.java`:

```java
package com.exceltodb.controller;

import com.exceltodb.model.TablePreviewResponse;
import com.exceltodb.service.DbService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExcelController.class)
class ExcelControllerTablePreviewTest {

    @Autowired MockMvc mvc;

    @MockBean DbService dbService;
    @MockBean com.exceltodb.service.ExcelParserService excelParserService;
    @MockBean com.exceltodb.service.TableMatcherService tableMatcherService;
    @MockBean com.exceltodb.service.ImportService importService;

    @Test
    void tablePreview_returnsColumnsAndRows() throws Exception {
        TablePreviewResponse resp = new TablePreviewResponse();
        resp.setTableName("orders");
        resp.setColumns(List.of("id", "order_no"));
        resp.setRows(List.of(Map.of("id", 1, "order_no", "A001")));

        Mockito.when(dbService.getTablePreview("prod_erp", "orders", 5)).thenReturn(resp);

        mvc.perform(get("/api/table-preview")
                .param("databaseId", "prod_erp")
                .param("tableName", "orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tableName").value("orders"))
            .andExpect(jsonPath("$.columns[0]").value("id"))
            .andExpect(jsonPath("$.rows[0].id").value(1));
    }
}
```

- [ ] **Step 2: Implement endpoint in controller**

Add to `ExcelController`:

```java
@GetMapping("/table-preview")
public ResponseEntity<TablePreviewResponse> getTablePreview(@RequestParam String databaseId,
                                                            @RequestParam String tableName,
                                                            @RequestParam(defaultValue = "5") int limit) {
    try {
        return ResponseEntity.ok(dbService.getTablePreview(databaseId, tableName, limit));
    } catch (Exception e) {
        return ResponseEntity.status(500).build();
    }
}
```

- [ ] **Step 3: Run test**

From `server/`:

`mvn -q test -Dtest=ExcelControllerTablePreviewTest`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/controller/ExcelController.java server/src/test/java/com/exceltodb/controller/ExcelControllerTablePreviewTest.java
git commit -m "feat(server): add table preview endpoint"
```

---

### Task 4: Frontend modal UI + conditional preview button

**Files:**
- Modify: `client/src/components/TableRecommend.vue`

- [ ] **Step 1: Track active score and threshold in UI state**

Add refs/computed so that:
- Manual mode uses `manualValidationResult.score/threshold` as the active score when manual selection is active
- Recommend mode uses the chosen recommendation’s `score` and `recommendThreshold` when recommend selection is active

Expose computed:
- `activeMatchScore`
- `activeThreshold` (always 90 today)
- `canPreview` = `selectedTableInfo && activeMatchScore >= activeThreshold`

- [ ] **Step 2: Add preview button to import settings card**

In the “导入设置” form area, add:
- Button: `预览目标表(字段+5行)` with `:disabled="!canPreview"`

- [ ] **Step 3: Add dialog state and fetch function**

Add refs:
- `previewDialogVisible`
- `previewLoading`
- `previewError`
- `previewColumns` (string[])
- `previewRows` (array of objects)

Fetch:
- call `GET /api/table-preview?databaseId=...&tableName=...&limit=5`
- show loading; on success fill columns+rows; on error set previewError

- [ ] **Step 4: Render dialog content**

Dialog header shows:
- `目标表预览：${selectedTableInfo.name}`
- `匹配度 ${activeMatchScore}%（阈值 ${activeThreshold}%）`

Body:
- Columns list (e.g. tags or small table)
- Data table:
  - if rows empty: show “该表暂无数据”
  - else show up to 5 rows, with columns as table headers

- [ ] **Step 5: Manual verification**

From `client/`:

`npm run dev`

Checks:
- Button appears only when selection exists and score ≥ 90
- Clicking opens dialog and loads data
- 0-row case shows “该表暂无数据” and still shows columns

- [ ] **Step 6: Build**

From `client/`:

`npm run build`

Expected: build succeeds

- [ ] **Step 7: Commit**

```bash
git add client/src/components/TableRecommend.vue
git commit -m "feat(client): add table preview modal for selected table"
```

---

### Task 5: End-to-end verification

**Files:** none

- [ ] **Step 1: Run server**

From `server/`:

`mvn spring-boot:run`

- [ ] **Step 2: Run client**

From `client/`:

`npm run dev`

- [ ] **Step 3: Full flow**

1) Select DB → upload file → preview columns → select table (manual or recommend) with score ≥ 90
2) In import settings, click preview button
3) Confirm dialog shows:
   - correct table name
   - score from UI state
   - columns list
   - 0-5 rows from DB

---

## Plan self-review checklist (filled)

- **Spec coverage:** preview button gating (score ≥ 90), modal UX, backend endpoint shape, 0-row behavior all covered in Tasks 3–4.
- **Placeholder scan:** no TBD/TODO; every step contains code/commands.
- **Type consistency:** endpoint `/api/table-preview` and response `{ tableName, columns, rows }` consistent across tasks.

