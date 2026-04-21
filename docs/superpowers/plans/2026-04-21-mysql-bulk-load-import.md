# MySQL Bulk Load Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Excel/CSV imports fast by switching to MySQL `LOAD DATA LOCAL INFILE` with a staging-table merge that still supports 覆盖/更新/忽略 strategies.

**Architecture:** Convert Excel→standard CSV (and normalize CSV if needed), bulk-load into a per-request staging table, then merge into target table using strategy-specific SQL inside one transaction. Preserve existing heartbeat stages with a few well-timed updates.

**Tech Stack:** Spring Boot 3 (Java 17), MySQL Connector/J 8.2, HikariCP, Apache POI, OpenCSV, JUnit 5, (add) Testcontainers MySQL for integration tests.

---

## Scope check

This plan touches one subsystem (server-side import pipeline). Frontend remains unchanged (still calls `/api/import` and polls heartbeat).

---

## File structure (units & responsibilities)

**Modify**
- `server/src/main/java/com/exceltodb/config/DataSourceConfig.java`
  - Add JDBC URL params required by `LOAD DATA LOCAL INFILE`.
- `server/src/main/java/com/exceltodb/service/ImportService.java`
  - Route import to new bulk-load path; keep existing JDBC batch insert as fallback / feature-flagged.
- `server/src/main/java/com/exceltodb/service/ExcelParserService.java`
  - Add a method to materialize a “standard CSV” file for both CSV and Excel inputs (no heavy type inference).

**Create**
- `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`
  - Own end-to-end staging table creation, `LOAD DATA`, merge SQL, cleanup, and heartbeat updates.
- `server/src/main/java/com/exceltodb/service/CsvStandardizer.java`
  - Write RFC4180-ish CSV rows (`"` quoting, `""` escaping, `\n` newline) consistently.

**Tests**
- `server/pom.xml`
  - Add Testcontainers deps.
- `server/src/test/java/com/exceltodb/service/BulkLoadImportServiceIT.java`
  - MySQL container integration test that proves 覆盖/更新/忽略 correctness and that `LOAD DATA LOCAL INFILE` is actually used.

---

### Task 1: Enable `LOAD DATA LOCAL INFILE` at JDBC layer

**Files:**
- Modify: `server/src/main/java/com/exceltodb/config/DataSourceConfig.java`

- [ ] **Step 1: Update JDBC URL to allow LOCAL INFILE**

Change `setJdbcUrl(...)` to include `allowLoadLocalInfile=true`.

```java
config.setJdbcUrl(String.format(
  "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowLoadLocalInfile=true",
  dbInfo.getHost(), dbInfo.getPort(), dbInfo.getDatabase()
));
```

- [ ] **Step 2: Add a focused unit test? (skip)**

This is primarily a configuration wiring change; it will be validated by the integration tests in Task 6.

- [ ] **Step 3: Build**

Run: `cd server; mvn -q -DskipTests package`  
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

Run:

```bash
git add server/src/main/java/com/exceltodb/config/DataSourceConfig.java
git commit -m "perf(server): enable mysql local infile in jdbc url"
```

---

### Task 2: Add CSV standard writer (RFC4180-ish)

**Files:**
- Create: `server/src/main/java/com/exceltodb/service/CsvStandardizer.java`
- Test: `server/src/test/java/com/exceltodb/service/CsvStandardizerTest.java`

- [ ] **Step 1: Write failing test**

Create `CsvStandardizerTest.java`:

```java
package com.exceltodb.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CsvStandardizerTest {
    @Test
    void writesHeaderAndEscapesQuotesAndNewlines() {
        String csv = CsvStandardizer.toCsv(
                List.of("a", "b", "c"),
                List.of(
                        new String[]{"x", "he\"llo", "line1\nline2"},
                        new String[]{"", null, "z"}
                )
        );

        // Header + 2 data rows, always \n newlines, always quoted fields.
        assertEquals(
                "\"a\",\"b\",\"c\"\n" +
                "\"x\",\"he\"\"llo\",\"line1\nline2\"\n" +
                "\"\",\"\",\"z\"\n",
                csv
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server; mvn -q -Dtest=CsvStandardizerTest test`  
Expected: FAIL with “cannot find symbol CsvStandardizer”

- [ ] **Step 3: Implement CsvStandardizer**

Create `CsvStandardizer.java`:

```java
package com.exceltodb.service;

import java.util.List;

public final class CsvStandardizer {
    private CsvStandardizer() {}

    public static String toCsv(List<String> header, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        writeRow(sb, header.toArray(new String[0]));
        for (String[] r : rows) {
            writeRow(sb, r);
        }
        return sb.toString();
    }

    public static void writeRow(StringBuilder sb, String[] fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(fields[i])).append('"');
        }
        sb.append('\n');
    }

    private static String escape(String s) {
        if (s == null) return "";
        // RFC4180-style: quote by doubling.
        return s.replace("\"", "\"\"");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server; mvn -q -Dtest=CsvStandardizerTest test`  
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/CsvStandardizer.java server/src/test/java/com/exceltodb/service/CsvStandardizerTest.java
git commit -m "feat(server): add standard csv writer for bulk load"
```

---

### Task 3: Materialize “standard CSV” for both CSV and Excel uploads

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/ExcelParserService.java`
- Test: `server/src/test/java/com/exceltodb/service/ExcelParserServiceStandardCsvTest.java`

- [ ] **Step 1: Write failing test (CSV path)**

Create `ExcelParserServiceStandardCsvTest.java`:

```java
package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExcelParserServiceStandardCsvTest {
    @Test
    void createsStandardCsvFileForUploadedCsv() throws Exception {
        AppConfig cfg = new AppConfig();
        cfg.setUploadTempPath(Files.createTempDirectory("uploads").toString());
        ExcelParserService svc = new ExcelParserService(cfg);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "a.csv",
                "text/csv",
                ("col1,col2\n" +
                 "x,\"he\"\"llo\"\n").getBytes()
        );
        var parse = svc.parseAndSave(file);

        Path standard = svc.ensureStandardCsv(parse.getFilename(), 0);
        assertTrue(Files.exists(standard));
        String content = Files.readString(standard);
        // Always quoted output from CsvStandardizer
        assertTrue(content.startsWith("\"col1\",\"col2\"\n"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server; mvn -q -Dtest=ExcelParserServiceStandardCsvTest test`  
Expected: FAIL with “method ensureStandardCsv not found”

- [ ] **Step 3: Implement `ensureStandardCsv(filename, sheetIndex)`**

In `ExcelParserService.java`, add:

```java
public Path ensureStandardCsv(String filename, int sheetIndex) throws IOException, InvalidFormatException {
    Path input = uploadedFiles.get(filename);
    if (input == null) throw new RuntimeException("文件不存在或已过期: " + filename);

    Path uploadDir = Paths.get(appConfig.getUploadTempPath()).toAbsolutePath().normalize();
    Files.createDirectories(uploadDir);
    String outName = filename + ".standard.csv";
    Path out = uploadDir.resolve(outName).toAbsolutePath().normalize();

    String lower = filename.toLowerCase();
    if (lower.endsWith(".csv")) {
        // Read using detected charset + OpenCSV parser, then rewrite using CsvStandardizer rules.
        try (CSVReader reader = createCsvReader(input);
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {

            String[] header = reader.readNext();
            if (header == null) throw new RuntimeException("CSV文件为空");
            stripBomInPlace(header);
            CsvStandardizer.writeRow(new StringBuilderProxy(w), header);

            String[] line;
            while ((line = reader.readNext()) != null) {
                // Normalize to header length (pad missing with empty).
                String[] normalized = new String[header.length];
                for (int i = 0; i < header.length; i++) {
                    normalized[i] = (i < line.length && line[i] != null) ? line[i] : "";
                }
                CsvStandardizer.writeRow(new StringBuilderProxy(w), normalized);
            }
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
        return out;
    }

    if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
        try (Workbook workbook = WorkbookFactory.create(input.toFile());
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            Sheet sheet = requireSheet(workbook, sheetIndex);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new RuntimeException("Excel该工作表为空或没有表头");

            int lastCellNum = headerRow.getLastCellNum();
            String[] header = new String[lastCellNum];
            for (int i = 0; i < lastCellNum; i++) {
                header[i] = getCellValueAsString(headerRow.getCell(i));
            }
            CsvStandardizer.writeRow(new StringBuilderProxy(w), header);

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String[] fields = new String[lastCellNum];
                for (int c = 0; c < lastCellNum; c++) {
                    fields[c] = getCellValueAsString(row.getCell(c));
                }
                CsvStandardizer.writeRow(new StringBuilderProxy(w), fields);
            }
        }
        return out;
    }

    throw new RuntimeException("不支持的文件格式");
}

/**
 * Adapter to keep CsvStandardizer API simple while streaming to file.
 */
static final class StringBuilderProxy extends StringBuilder {
    private final BufferedWriter w;
    StringBuilderProxy(BufferedWriter w) { this.w = w; }
    @Override public StringBuilder append(String str) {
        try { w.write(str); } catch (IOException e) { throw new RuntimeException(e); }
        return this;
    }
    @Override public StringBuilder append(char c) {
        try { w.write(c); } catch (IOException e) { throw new RuntimeException(e); }
        return this;
    }
}
```

Then update `CsvStandardizer.writeRow(...)` call sites accordingly (using the proxy builder).

- [ ] **Step 4: Run test**

Run: `cd server; mvn -q -Dtest=ExcelParserServiceStandardCsvTest test`  
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/ExcelParserService.java server/src/test/java/com/exceltodb/service/ExcelParserServiceStandardCsvTest.java
git commit -m "feat(server): generate standard csv for excel/csv bulk load"
```

---

### Task 4: Implement bulk-load pipeline (staging table + load data + merge)

**Files:**
- Create: `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`

- [ ] **Step 1: Create `BulkLoadImportService` skeleton**

Create `BulkLoadImportService.java`:

```java
package com.exceltodb.service;

import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ImportStage;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

@Service
public class BulkLoadImportService {
    private final ExcelParserService excelParserService;
    private final ImportHeartbeatStore heartbeatStore;

    public BulkLoadImportService(ExcelParserService excelParserService, ImportHeartbeatStore heartbeatStore) {
        this.excelParserService = excelParserService;
        this.heartbeatStore = heartbeatStore;
    }

    public ImportResult importWithLoadData(DataSource ds, ImportRequest request) {
        String requestId = request.getRequestId();
        long processedRows = 0;

        ImportResult result = new ImportResult();
        String targetTable = request.getTableName();
        String tmpTable = "__import_" + safeId(requestId);

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if ("TRUNCATE".equals(request.getImportMode())) {
                    if (requestId != null && !requestId.isBlank()) heartbeatStore.update(requestId, ImportStage.TRUNCATE, 0, "");
                    try (Statement st = conn.createStatement()) {
                        st.execute("TRUNCATE TABLE `" + escapeIdent(targetTable) + "`");
                    }
                }

                if (requestId != null && !requestId.isBlank()) heartbeatStore.update(requestId, ImportStage.READING, 0, "");
                Path csvPath = excelParserService.ensureStandardCsv(request.getFilename(), request.getSheetIndex() == null ? 0 : request.getSheetIndex());

                createStagingTable(conn, tmpTable, targetTable);

                if (requestId != null && !requestId.isBlank()) heartbeatStore.update(requestId, ImportStage.INSERTING, 0, "LOAD DATA");
                loadDataLocal(conn, tmpTable, csvPath);

                // Optional: processed rows in tmp
                processedRows = countRows(conn, tmpTable);
                if (requestId != null && !requestId.isBlank()) heartbeatStore.update(requestId, ImportStage.INSERTING, processedRows, "MERGE");

                mergeIntoTarget(conn, tmpTable, targetTable, request.getConflictStrategy());

                dropTableQuietly(conn, tmpTable);

                if (requestId != null && !requestId.isBlank()) heartbeatStore.update(requestId, ImportStage.COMMITTING, processedRows, "");
                conn.commit();
                result.setSuccess(true);
                result.setImportedRows((int) processedRows);
                result.setMessage("导入成功");
                if (requestId != null && !requestId.isBlank()) heartbeatStore.success(requestId, processedRows);
                return result;
            } catch (Exception e) {
                conn.rollback();
                dropTableQuietly(conn, tmpTable);
                if (requestId != null && !requestId.isBlank()) heartbeatStore.error(requestId, processedRows, e.getMessage());
                result.setSuccess(false);
                result.setMessage("导入失败: " + e.getMessage());
                return result;
            }
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("导入失败: " + e.getMessage());
            return result;
        }
    }

    private void createStagingTable(Connection conn, String tmp, String target) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + escapeIdent(tmp) + "`");
            st.execute("CREATE TABLE `" + escapeIdent(tmp) + "` LIKE `" + escapeIdent(target) + "`");
        }
    }

    private void loadDataLocal(Connection conn, String tmp, Path csv) throws Exception {
        String sql = "LOAD DATA LOCAL INFILE ? INTO TABLE `" + escapeIdent(tmp) + "` " +
                "CHARACTER SET utf8mb4 " +
                "FIELDS TERMINATED BY ',' ENCLOSED BY '\"' " +
                "LINES TERMINATED BY '\\n' " +
                "IGNORE 1 LINES";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, csv.toAbsolutePath().toString());
            ps.execute();
        }
    }

    private void mergeIntoTarget(Connection conn, String tmp, String target, String strategy) throws Exception {
        String cols = "*";
        // NOTE: simplified; replaced in Task 5 with explicit columns list.
        try (Statement st = conn.createStatement()) {
            if ("IGNORE".equals(strategy)) {
                st.execute("INSERT IGNORE INTO `" + escapeIdent(target) + "` SELECT " + cols + " FROM `" + escapeIdent(tmp) + "`");
            } else if ("UPDATE".equals(strategy)) {
                // Task 5 will build explicit column list + ON DUPLICATE KEY UPDATE.
                st.execute("INSERT INTO `" + escapeIdent(target) + "` SELECT " + cols + " FROM `" + escapeIdent(tmp) + "`");
            } else {
                st.execute("INSERT INTO `" + escapeIdent(target) + "` SELECT " + cols + " FROM `" + escapeIdent(tmp) + "`");
            }
        }
    }

    private long countRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM `" + escapeIdent(table) + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void dropTableQuietly(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + escapeIdent(table) + "`");
        } catch (Exception ignored) {}
    }

    private static String safeId(String requestId) {
        if (requestId == null) return "no_request";
        return requestId.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String escapeIdent(String ident) {
        return ident.replace("`", "``");
    }
}
```

This compiles but has deliberately minimal merge logic; Task 5 will replace it with correct column list & SQL for UPDATE.

- [ ] **Step 2: Compile**

Run: `cd server; mvn -q -DskipTests package`  
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/BulkLoadImportService.java
git commit -m "feat(server): add bulk load import service scaffold"
```

---

### Task 5: Correct merge SQL (explicit columns, UPDATE/IGNORE/COVER semantics)

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`
- Modify: `server/src/main/java/com/exceltodb/service/DbService.java` (add helper to fetch ordered column names)

- [ ] **Step 1: Add DB helper for ordered column list**

In `DbService.java`, add:

```java
public List<String> getOrderedColumnNames(String databaseId, String tableName) {
    try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection()) {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conn.getCatalog());
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.add(rs.getString(1));
            }
        }
        return columns;
    } catch (SQLException e) {
        throw new RuntimeException("获取列信息失败: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 2: Use explicit columns for staging + merge**

Update `BulkLoadImportService`:

1) Change `createStagingTable` to create `tmp LIKE target` (keep)  
2) Change `loadDataLocal` to specify the target columns list to align with CSV header ordering. Build:

```sql
LOAD DATA LOCAL INFILE ?
INTO TABLE `tmp`
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(`col1`,`col2`,...)
SET `col1` = NULLIF(`col1`,''), ...
```

Implementation detail: MySQL allows loading directly into column list, but `NULLIF` requires user variables. Use:

```sql
(@c1,@c2,...)
SET `col1` = NULLIF(@c1,''), `col2` = NULLIF(@c2,''), ...
```

3) Implement strategy merge:

- IGNORE:

```sql
INSERT IGNORE INTO `target` (`c1`,`c2`,...)
SELECT `c1`,`c2`,... FROM `tmp`;
```

- UPDATE:

```sql
INSERT INTO `target` (`c1`,`c2`,...)
SELECT `c1`,`c2`,... FROM `tmp`
ON DUPLICATE KEY UPDATE
  `c1`=VALUES(`c1`), `c2`=VALUES(`c2`), ...;
```

- COVER:
  - If request.importMode == TRUNCATE (already executed): just insert from tmp
  - Else (if you want “覆盖策略” independent of importMode): explicitly TRUNCATE then insert

Also ensure identifiers are escaped with backticks and backtick-doubling.

- [ ] **Step 3: Compile**

Run: `cd server; mvn -q -DskipTests package`  
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/com/exceltodb/service/DbService.java server/src/main/java/com/exceltodb/service/BulkLoadImportService.java
git commit -m "feat(server): merge staging table into target for ignore/update/cover"
```

---

### Task 6: Route `/api/import` to bulk-load path (feature flag + fallback)

**Files:**
- Modify: `server/src/main/java/com/exceltodb/config/AppConfig.java`
- Modify: `server/src/main/java/com/exceltodb/service/ImportService.java`

- [ ] **Step 1: Add config toggle**

In `AppConfig.java`, add:

```java
private boolean bulkLoadEnabled = true;
```

And load from `config.yaml` (extend existing `import` block):

```java
@SuppressWarnings("unchecked")
Map<String, Object> importConfig = (Map<String, Object>) config.get("import");
if (importConfig != null) {
    batchSize = (Integer) importConfig.getOrDefault("batchSize", batchSize);
    Object bulk = importConfig.get("bulkLoadEnabled");
    if (bulk != null) {
        bulkLoadEnabled = Boolean.parseBoolean(String.valueOf(bulk));
    }
}
```

In `server/src/main/resources/config.yaml` (example only; do not commit secrets changes), allow:

```yaml
import:
  batchSize: 5000
  bulkLoadEnabled: true
```

- [ ] **Step 2: Inject `BulkLoadImportService` and route**

In `ImportService.java` constructor, inject `BulkLoadImportService` and in `importData`:

- If `appConfig.isBulkLoadEnabled()` is true, call `bulkLoadImportService.importWithLoadData(ds, request)` and return it.
- Otherwise, run the existing JDBC batch insert path (current code).

Keep heartbeat start logic as-is (controller + import service already start).

- [ ] **Step 3: Unit test routing**

Create `ImportServiceRoutingTest.java` using mocks:

- When `bulkLoadEnabled=true`, verify `BulkLoadImportService.importWithLoadData(...)` is invoked once and JDBC path is not.

- [ ] **Step 4: Run tests**

Run: `cd server; mvn test`  
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/exceltodb/config/AppConfig.java server/src/main/java/com/exceltodb/service/ImportService.java server/src/test/java/com/exceltodb/service/ImportServiceRoutingTest.java
git commit -m "feat(server): route import to bulk load with feature flag"
```

---

### Task 7: Add Testcontainers integration test (proves LOAD DATA + strategy semantics)

**Files:**
- Modify: `server/pom.xml`
- Create: `server/src/test/java/com/exceltodb/service/BulkLoadImportServiceIT.java`

- [ ] **Step 1: Add Testcontainers deps**

In `server/pom.xml`, add (test scope):

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>1.20.4</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>mysql</artifactId>
  <version>1.20.4</version>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write integration test**

Create `BulkLoadImportServiceIT.java`:

```java
package com.exceltodb.service;

import com.exceltodb.model.ImportRequest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BulkLoadImportServiceIT {
    @Test
    void loadDataAndMergeIgnoreUpdateCover() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test")
                .withCommand("--local-infile=1")) {
            mysql.start();

            // DataSource via Hikari is fine, but simplest: DriverManager-based DataSource wrapper.
            com.zaxxer.hikari.HikariConfig hc = new com.zaxxer.hikari.HikariConfig();
            hc.setJdbcUrl(mysql.getJdbcUrl() + "&allowLoadLocalInfile=true");
            hc.setUsername(mysql.getUsername());
            hc.setPassword(mysql.getPassword());
            DataSource ds = new com.zaxxer.hikari.HikariDataSource(hc);

            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE target (id INT PRIMARY KEY, v VARCHAR(50))");
            }

            // Prepare a “uploaded file” csv and register it via ExcelParserService parseAndSave isn't needed;
            // we'll directly place it and call ensureStandardCsv in unit tests, but here we can just load a standard csv.
            Path dir = Files.createTempDirectory("bulkload");
            Path csv = dir.resolve("data.standard.csv");
            Files.writeString(csv, "\"id\",\"v\"\n\"1\",\"a\"\n\"2\",\"b\"\n");

            // Minimal ExcelParserService stub: use a real instance but inject uploadedFiles via parseAndSave (simpler).
            // Here, we call BulkLoadImportService.loadDataLocal indirectly by placing the file path into request filename.
            // Implementation detail: in the real code, BulkLoadImportService uses ExcelParserService.ensureStandardCsv,
            // so in production we rely on uploads dir. For IT, we can subclass or add an overload.
            //
            // For plan execution: implement an overload importWithLoadData(DataSource, ImportRequest, Path standardCsv)
            // and call that from tests.
            //
            // This test asserts strategy semantics; it will pass once overload exists.
            String requestId = UUID.randomUUID().toString();
            ImportRequest req = new ImportRequest();
            req.setRequestId(requestId);
            req.setTableName("target");
            req.setImportMode("INCREMENTAL");
            req.setConflictStrategy("IGNORE");

            ImportHeartbeatStore hb = new ImportHeartbeatStore();
            ExcelParserService eps = null; // not used by overload
            BulkLoadImportService svc = new BulkLoadImportService(eps, hb);

            var r1 = svc.importWithLoadData(ds, req, csv);
            assertTrue(r1.isSuccess());
            assertEquals(2, r1.getImportedRows());

            // IGNORE with duplicate id=2 should keep existing.
            Files.writeString(csv, "\"id\",\"v\"\n\"2\",\"bb\"\n\"3\",\"c\"\n");
            var r2 = svc.importWithLoadData(ds, req, csv);
            assertTrue(r2.isSuccess());
            try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM target")) {
                rs.next();
                assertEquals(3, rs.getInt(1));
            }

            // UPDATE should update id=2
            req.setConflictStrategy("UPDATE");
            Files.writeString(csv, "\"id\",\"v\"\n\"2\",\"bbb\"\n");
            var r3 = svc.importWithLoadData(ds, req, csv);
            assertTrue(r3.isSuccess());
            try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT v FROM target WHERE id=2")) {
                rs.next();
                assertEquals("bbb", rs.getString(1));
            }

            // COVER via TRUNCATE
            req.setImportMode("TRUNCATE");
            req.setConflictStrategy("IGNORE");
            Files.writeString(csv, "\"id\",\"v\"\n\"9\",\"z\"\n");
            var r4 = svc.importWithLoadData(ds, req, csv);
            assertTrue(r4.isSuccess());
            try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM target")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
```

**Required code change to support test:** add overload in `BulkLoadImportService`:

```java
public ImportResult importWithLoadData(DataSource ds, ImportRequest request, Path standardCsvPath) {
    // same body as importWithLoadData(...), but skip ensureStandardCsv and use standardCsvPath directly
}
```

- [ ] **Step 3: Run integration test**

Run: `cd server; mvn -q -Dtest=BulkLoadImportServiceIT test`  
Expected: BUILD SUCCESS (Docker required)

- [ ] **Step 4: Commit**

```bash
git add server/pom.xml server/src/main/java/com/exceltodb/service/BulkLoadImportService.java server/src/test/java/com/exceltodb/service/BulkLoadImportServiceIT.java
git commit -m "test(server): add mysql bulk load integration tests"
```

---

### Task 8: End-to-end verification (manual) + performance check

**Files:**
- None (runtime validation)

- [ ] **Step 1: Run backend**

Run: `cd server; mvn spring-boot:run`

- [ ] **Step 2: Run frontend**

Run: `cd client; npm run dev`

- [ ] **Step 3: Manual import checks**

Using UI:

- Import a ~7万行 CSV into a table with primary key:
  - Strategy IGNORE: verify duplicates skipped
  - Strategy UPDATE: verify duplicates updated
  - ImportMode TRUNCATE: verify atomic overwrite semantics
- Confirm heartbeat stage transitions still update and no false “stall” prompts.

- [ ] **Step 4: Quick timing**

Record wall-clock time for:
- Excel→CSV conversion (from heartbeat `READING` start to `LOAD DATA` start)
- Bulk load + merge time (from `LOAD DATA` start to `COMMITTING`)

Success criteria: noticeably closer to dbeaver import times than previous JDBC batch insert.

---

## Plan self-review

**Spec coverage**
- Bulk load path: Tasks 1,4,5,6
- Excel→CSV standardization: Tasks 2,3
- Strategies 覆盖/更新/忽略: Task 5 + Task 7
- Heartbeat stages: Task 4 + Task 8 verification

**Placeholder scan**
- No “TBD/TODO/implement later” steps remain; any “NOTE” items are converted into explicit steps (e.g., overload for IT).

**Type consistency**
- Reuses existing `ImportRequest` fields: `filename`, `databaseId`, `tableName`, `importMode`, `conflictStrategy`, `sheetIndex`, `requestId`

