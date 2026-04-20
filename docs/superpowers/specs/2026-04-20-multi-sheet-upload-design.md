# Multi-Sheet Excel Selection — Design Specification

**Date:** 2026-04-20  
**Status:** Approved for implementation planning  
**Scope:** Allow business users to choose which Excel worksheet to import when a workbook contains multiple sheets. CSV and single-sheet workbooks behave as today.

---

## 1. Goals and Non-Goals

### 1.1 Goals

- On the **upload file** step (`FileUpload.vue`), when the uploaded file is `.xlsx` or `.xls` with **more than one sheet**, show a control so the user can **confirm which sheet** to use before continuing.
- **Single-sheet Excel:** No sheet-selection UI; flow and copy match current behavior (user choice **A** from brainstorming).
- **CSV:** Unchanged; no sheet list.
- Selected sheet must drive **preview**, **table recommendation** (column list), and **import** consistently end-to-end.

### 1.2 Non-Goals

- Multi-sheet CSV or multiple workbooks.
- New wizard step between upload and preview (selection stays on upload page).
- Importing multiple sheets in one run.

---

## 2. User Experience (`FileUpload.vue`)

### 2.1 CSV

- No `sheets` UI.
- Success path unchanged.

### 2.2 Excel with exactly one sheet

- Do **not** show sheet picker.
- Success messaging may still show sheet name and row count (current style).

### 2.3 Excel with two or more sheets

- After successful upload, show a **single-select** control (`el-radio-group` or `el-select`) listing each sheet with helpful metadata (at minimum: **sheet name** and **row count** per existing POI semantics used elsewhere, e.g. `lastRowNum`-style counts; label copy should state whether count includes header row if that is what the backend returns).
- **Default selection:** sheet at **index 0** (preserves legacy “first sheet” behavior).
- **Changing selection:** Re-fetch metadata for the chosen sheet using the same uploaded `filename` (see §3) and update local state: `sheetIndex`, `columns`, `sheetName`, `rowCount`, and `uploadedFile` injected for downstream steps.
- **Next button:** Enabled when upload succeeded and, for multi-sheet, a valid sheet is selected (default 0 satisfies this without extra clicks).

---

## 3. API and Data Contracts

### 3.1 `POST /api/upload` — `ParseResult`

Add fields:

| Field | Type | Description |
|--------|------|-------------|
| `sheetIndex` | `int` | Active sheet index (0-based). Default `0` in response for first parse. |
| `sheets` | array or null | For **Excel only**: ordered list `{ index, name, rowCount }` for each sheet. For CSV: omit or empty. |
| `columns`, `rowCount`, `sheetName` | unchanged meaning | Reflect the sheet identified by `sheetIndex` (default 0 on upload). |

Single-sheet Excel may return `sheets` with one element (optional); UI hides picker when `sheets == null || sheets.length <= 1`.

### 3.2 `GET /api/preview/{filename}`

- Add optional query parameter **`sheetIndex`** (integer, default **0**).
- Response `PreviewResult` must match that sheet (`sheetName`, `columns`, `previewRows`, `totalRows`, etc.).

**Caching:** Today preview may be cached by `filename` only. Change to a composite key **`filename` + `sheetIndex`** (or invalidate when `sheetIndex` differs) so switching sheets cannot return a stale preview.

### 3.3 `POST /api/recommend` and `POST /api/import`

- Request body includes optional **`sheetIndex`** (default **0**).
- Backend uses this index whenever reading Excel rows for recommendation fallbacks and for `readAllData` / import.
- Frontend must pass the same `sheetIndex` stored on `uploadedFile` when calling preview (step 3), recommend (step 4), and import (step 5).

---

## 4. Backend Behavior (`ExcelParserService` and callers)

### 4.1 Upload (`parseAndSave` / `parseExcel`)

- After saving the file, open the workbook once.
- Enumerate all sheets; build `sheets[]` with stable **0-based indices** aligned with POI `getSheetAt(i)`.
- Parse header + row count for **`sheetIndex`** (default `0`) into `columns`, `rowCount`, `sheetName`.

### 4.2 Preview and full read

- Replace all Excel paths that use **`getSheetAt(0)`** with **`getSheetAt(sheetIndex)`** where `sheetIndex` is supplied (controller/service), defaulting to **0** if missing.
- Validate bounds: if `sheetIndex` is out of range, return a clear client-facing error (4xx with message or consistent error payload as used elsewhere).

### 4.3 Import (`ImportService`, `ImportRequest`)

- Add optional `sheetIndex` to `ImportRequest` (Integer or `int` with default 0 in service layer).
- Pass through to `ExcelParserService.readAllData` (or equivalent) so imported rows match the selected sheet.

### 4.4 Edge cases

- **Empty sheet or missing header row:** Same validation spirit as today, applied to the **selected** sheet; user can try another sheet or fix the file.
- **Hidden / chart sheets:** Include in enumeration for v1 (same as POI iteration); filtering can be a later enhancement.

---

## 5. Frontend Propagation

| Step | Change |
|------|--------|
| `FileUpload.vue` | Sheet list UI per §2; keep `uploadedFile` in sync with `sheetIndex`, `columns`, `rowCount`, `sheetName`. |
| `DataPreview.vue` | `GET /api/preview/{filename}?sheetIndex={uploadedFile.sheetIndex \|\| 0}` |
| `TableRecommend.vue` | Include `sheetIndex` in `POST /api/recommend` body (and keep sending `columns` from `previewData` after preview loaded for that sheet). |
| `ImportProgress.vue` (or wherever import POST is built) | Include `sheetIndex` in `POST /api/import` body. |

---

## 6. Testing

- **Fixture:** Multi-sheet `.xlsx` with different headers per sheet; assert preview and import use the selected sheet.
- **Regression:** Single-sheet xlsx, `.xls`, `.csv`; default `sheetIndex` omitted behaves as index 0.
- **Cache:** Change `sheetIndex` and confirm preview body changes (no stale first sheet).

---

## 7. Self-Review (2026-04-20)

- **Placeholders:** None; numeric defaults and field names are explicit.
- **Consistency:** Single-sheet UX (no picker) matches approved option A; default index 0 matches legacy first-sheet behavior.
- **Scope:** Single feature slice; no session store, no extra wizard step.
- **Ambiguity resolved:** Sheet identity is **0-based index** end-to-end; sheet **name** is display-only. Row count semantics follow existing POI usage; UI labels should match backend meaning.

---

## 8. Implementation Follow-Up

After this document is reviewed in the repository, create an implementation plan via the **writing-plans** workflow (separate artifact), then implement and verify with tests per project norms.
