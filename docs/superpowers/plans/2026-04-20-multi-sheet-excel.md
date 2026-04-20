# Multi-Sheet Excel Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users pick which Excel worksheet to import when a workbook has multiple sheets, while keeping CSV and single-sheet Excel UX unchanged.

**Architecture:** Extend `ParseResult` with a sheet catalog and `sheetIndex`; thread optional `sheetIndex` (default `0`) through `GET /api/preview`, `POST /api/recommend`, `POST /api/import`, and `createTable` paths. `ExcelParserService` uses `Workbook.getSheetAt(sheetIndex)` everywhere Excel was previously hard-coded to sheet 0. Preview cache keys include `sheetIndex`. `FileUpload.vue` shows a picker only when `sheets.length > 1` and refetches preview metadata when the user changes selection.

**Tech Stack:** Java 17, Spring Boot 3, Apache POI, Vue 3, Element Plus, Axios.

**Spec:** `docs/superpowers/specs/2026-04-20-multi-sheet-upload-design.md`

---

## File map

| File | Responsibility |
|------|----------------|
| `server/src/main/java/com/exceltodb/model/SheetSummary.java` | New DTO: `int index`, `String name`, `int rowCount` (POI `lastRowNum` semantics, same as today for “rows”). |
| `server/src/main/java/com/exceltodb/model/ParseResult.java` | Add `int sheetIndex`, `List<SheetSummary> sheets` (null or empty for CSV). |
| `server/src/main/java/com/exceltodb/model/ImportRequest.java` | Add `Integer sheetIndex` (null = 0). |
| `server/src/main/java/com/exceltodb/model/CreateTableRequest.java` | Add `Integer sheetIndex` for parity with `readAllData`. |
| `server/src/main/java/com/exceltodb/service/ExcelParserService.java` | Enumerate sheets on upload; `getPreview(filename, maxRows, sheetIndex)`; composite preview cache; `readAllData(filename, sheetIndex)`; all `getSheetAt(0)` → `getSheetAt(sheetIndex)` with bounds check. |
| `server/src/main/java/com/exceltodb/controller/ExcelController.java` | `getPreview`: `@RequestParam(defaultValue = "0") int sheetIndex`; `RecommendRequest` + recommend handler pass `sheetIndex` into `getPreview` fallback; `ImportRequest` / `CreateTableRequest` already carry new fields via JSON. |
| `server/src/main/java/com/exceltodb/service/ImportService.java` | Resolve `int sheetIndex = request.getSheetIndex() != null ? request.getSheetIndex() : 0`; pass to `readAllData` (both call sites). `createTable`: same for `CreateTableRequest`. |
| `client/src/components/FileUpload.vue` | Multi-sheet UI; sync `uploadedFile` with `sheetIndex` / columns / rowCount / sheetName on change via preview API. |
| `client/src/components/DataPreview.vue` | Append `sheetIndex` query to preview GET. |
| `client/src/components/TableRecommend.vue` | Send `sheetIndex` in recommend POST body. |
| `client/src/App.vue` | Include `sheetIndex` in `importParams` from `uploadedFile`. |
| `server/src/test/java/com/exceltodb/service/ExcelParserServiceMultiSheetTest.java` | New: POI-built two-sheet xlsx on disk, assert parser reads correct header per index. |

---

### Task 1: Models (`SheetSummary`, extend DTOs)

**Files:**
- Create: `server/src/main/java/com/exceltodb/model/SheetSummary.java`
- Modify: `server/src/main/java/com/exceltodb/model/ParseResult.java`
- Modify: `server/src/main/java/com/exceltodb/model/ImportRequest.java`
- Modify: `server/src/main/java/com/exceltodb/model/CreateTableRequest.java`

- [ ] **Step 1:** Add Lombok `@Data` class `SheetSummary` with fields `private int index; private String name; private int rowCount;`.

- [ ] **Step 2:** Extend `ParseResult` with `private int sheetIndex;` and `private List<SheetSummary> sheets;` (initialize `sheets` to `null` or empty list for CSV in parser).

- [ ] **Step 3:** Add `private Integer sheetIndex;` to `ImportRequest` and `CreateTableRequest`.

- [ ] **Step 4:** Run `cd server; mvn -q compile` — Expected: BUILD SUCCESS.

- [ ] **Step 5:** Commit  
  `git add server/src/main/java/com/exceltodb/model/`  
  `git commit -m "feat(models): add sheet summary and sheetIndex for multi-sheet excel"`

---

### Task 2: `ExcelParserService` — parse, preview cache, read

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/ExcelParserService.java`

- [ ] **Step 1:** Introduce helper `private String previewCacheKey(String filename, int sheetIndex)` returning e.g. `filename + "|" + sheetIndex`.

- [ ] **Step 2:** In `getPreview`, when `maxRows == DEFAULT_PREVIEW_ROWS`, use `previewCache.get(previewCacheKey(filename, sheetIndex))` and `put` the same way. Remove or bypass old filename-only cache behavior.

- [ ] **Step 3:** Change signature to `getPreview(String filename, int maxRows, int sheetIndex)` — default `sheetIndex` callers use `0`. In `getExcelPreview`, use `workbook.getSheetAt(sheetIndex)`; if `sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()`, throw `RuntimeException` with clear Chinese message.

- [ ] **Step 4:** Refactor `parseExcel(Path, String)` to:
  - Open workbook; build `List<SheetSummary>` in order `i = 0 .. n-1` with `name = sheet.getSheetName()`, `rowCount = sheet.getLastRowNum()` (document same semantics as current single-sheet `rowCount` in `ParseResult`).
  - Set `result.setSheets(summaries)` for Excel; for CSV branch set `sheets` to `null` or empty.
  - Set `result.setSheetIndex(0)` and parse columns from `workbook.getSheetAt(0)` header row (unchanged default).

- [ ] **Step 5:** Change `readAllData(String filename)` to `readAllData(String filename, int sheetIndex)` and `readExcelAllData` to use `getSheetAt(sheetIndex)` with same bounds check.

- [ ] **Step 6:** `mvn -q compile` — Expected: SUCCESS.

- [ ] **Step 7:** Commit  
  `git commit -am "feat(parser): sheet index for excel preview and readAllData"`

---

### Task 3: Controller and services wiring

**Files:**
- Modify: `server/src/main/java/com/exceltodb/controller/ExcelController.java`
- Modify: `server/src/main/java/com/exceltodb/service/ImportService.java`

- [ ] **Step 1:** `getPreview` method — add `@RequestParam(defaultValue = "0") int sheetIndex` and call `excelParserService.getPreview(filename, maxRows, sheetIndex)`.

- [ ] **Step 2:** In `RecommendRequest` inner class, add `private Integer sheetIndex;` with getter/setter. In `getRecommendation`, when calling `excelParserService.getPreview(request.getFilename(), 100, ...)`, use `int si = request.getSheetIndex() != null ? request.getSheetIndex() : 0`.

- [ ] **Step 3:** `ImportService.importData`: before `readAllData`, `int sheetIndex = request.getSheetIndex() != null ? request.getSheetIndex() : 0;` and pass to `excelParserService.readAllData(filename, sheetIndex)`.

- [ ] **Step 4:** `ImportService.createTable`: same for `request.getSheetIndex()` when calling `readAllData`.

- [ ] **Step 5:** `mvn test` (or `mvn -q test`) — Expected: no tests yet may still pass compile phase; if no tests, `mvn -q compile` is minimum.

- [ ] **Step 6:** Commit  
  `git commit -am "feat(api): pass sheetIndex through preview recommend import"`

---

### Task 4: Automated test (first backend test for this feature)

**Files:**
- Create: `server/src/test/java/com/exceltodb/service/ExcelParserServiceMultiSheetTest.java`

- [ ] **Step 1:** Add `server/src/test/java/com/exceltodb/service/ExcelParserServiceMultiSheetTest.java` using **`@SpringBootTest`** + **`@Autowired ExcelParserService`**. Build a two-sheet `.xlsx` **in memory** (do not rely on the client filename matching the saved `uniqueFilename`):

```java
byte[] bytes;
try (Workbook wb = new XSSFWorkbook()) {
    wb.createSheet("A").createRow(0).createCell(0).setCellValue("h0");
    wb.createSheet("B").createRow(0).createCell(0).setCellValue("h1");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    wb.write(bos);
    bytes = bos.toByteArray();
}
MockMultipartFile file = new MockMultipartFile(
    "file", "twosheets.xlsx",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
ParseResult pr = excelParserService.parseAndSave(file);
List<String[]> rows = excelParserService.readAllData(pr.getFilename(), 1);
assertFalse(rows.isEmpty());
assertEquals("h1", rows.get(0)[0]);
```

  Optionally assert `pr.getSheets().size() == 2` and `pr.getSheetIndex() == 0` once Task 2 is implemented.

- [ ] **Step 2:** Run `cd server; mvn -q test -Dtest=ExcelParserServiceMultiSheetTest` — Expected: BUILD SUCCESS.

- [ ] **Step 3:** Commit  
  `git add server/src/test/java/com/exceltodb/service/ExcelParserServiceMultiSheetTest.java`  
  `git commit -m "test(parser): multi-sheet readAllData uses sheet index"`

---

### Task 5: Frontend — upload UI and state

**Files:**
- Modify: `client/src/components/FileUpload.vue`

- [ ] **Step 1:** After successful upload, if `parseResult.sheets && parseResult.sheets.length > 1`, render `el-radio-group` (or `el-select`) bound to `selectedSheetIndex` (ref, initial `parseResult.sheetIndex ?? 0`).

- [ ] **Step 2:** On `selectedSheetIndex` change (user picks another sheet), call  
  `GET /api/preview/${encodeURIComponent(filename)}?sheetIndex=${selectedSheetIndex}&maxRows=100`  
  then update `parseResult` display fields and `uploadedFile.value` with `{ filename, sheetIndex, columns, sheetName, rowCount }` from response (`PreviewResult` has `totalRows`, `columns`, `sheetName` — map `rowCount` for alert from `totalRows` per current UI wording).

- [ ] **Step 3:** For `sheets.length <= 1`, do not render picker; keep current success alert using `parseResult` from upload response.

- [ ] **Step 4:** `canProceed` remains true after successful upload (default sheet 0 already valid for multi-sheet).

- [ ] **Step 5:** Manual: run `npm run dev`, upload two-sheet xlsx, switch sheet, confirm alert updates — Expected: columns/sheet name change.

- [ ] **Step 6:** Commit  
  `git commit -am "feat(client): sheet picker on upload for multi-sheet excel"`

---

### Task 6: Frontend — preview, recommend, import params

**Files:**
- Modify: `client/src/components/DataPreview.vue`
- Modify: `client/src/components/TableRecommend.vue`
- Modify: `client/src/App.vue`

- [ ] **Step 1:** `DataPreview.vue` — `axios.get(\`/api/preview/${uploadedFile.value.filename}?sheetIndex=${uploadedFile.value.sheetIndex ?? 0}\`)` (use `encodeURIComponent` on filename if it can contain special chars; align with backend).

- [ ] **Step 2:** `TableRecommend.vue` — add `sheetIndex: uploadedFile.value?.sheetIndex ?? 0` to recommend POST body.

- [ ] **Step 3:** `App.vue` `handleTableNext` — add `sheetIndex: uploadedFile.value?.sheetIndex ?? 0` to `importParams`.

- [ ] **Step 4:** `npm run build` in `client/` — Expected: success.

- [ ] **Step 5:** Commit  
  `git commit -am "feat(client): pass sheetIndex to preview recommend and import"`

---

### Task 7: Final verification

- [ ] **Step 1:** `cd server; mvn test` — Expected: all tests pass.

- [ ] **Step 2:** Manual E2E: single-sheet xlsx, CSV, two-sheet xlsx (switch sheet before next), confirm preview + import rows match selected sheet.

- [ ] **Step 3:** Commit any small fixes with message `fix: ...` if needed.

---

## Spec coverage (self-review)

| Spec section | Task(s) |
|--------------|---------|
| §2 UX single/multi sheet | Task 5 |
| §3 API ParseResult / preview / recommend / import | Tasks 1–3, 6 |
| §4 Backend POI + ImportService | Tasks 2–3 |
| §4 Edge bounds | Task 2 `getSheetAt` check |
| §5 Frontend propagation | Tasks 5–6 |
| §6 Testing | Task 4 + Task 7 |

No TBD sections; `CreateTableRequest.sheetIndex` added for consistency with `readAllData` usage in `createTable` (spec implied import path; create-table API uses same file).

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-04-20-multi-sheet-excel.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration. **REQUIRED SUB-SKILL:** `superpowers:subagent-driven-development`.

2. **Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.

**Which approach do you want?**

---

If you choose inline execution in Cursor, reply **「内联执行」** and I will implement task-by-task in this workspace without dispatching subagents (unless you prefer subagents).
