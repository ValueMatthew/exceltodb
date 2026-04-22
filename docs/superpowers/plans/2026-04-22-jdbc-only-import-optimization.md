# JDBC-only Import Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all MySQL bulk-load (`LOAD DATA LOCAL INFILE`) code paths and ship a faster, more stable JDBC-only importer that uses **column-type-driven conversion** and provides **strict row/column error reporting**.

**Architecture:** `ImportService` becomes the single import path. Before inserting, it fetches target table column metadata (`INFORMATION_SCHEMA.COLUMNS`) and builds per-column converters (`ColumnConverter`) that parse each cell exactly once and bind via `PreparedStatement#setXxx`. Import runs in batches and fails fast with precise error location when conversion fails.

**Tech Stack:** Spring Boot 3, Java 17, MySQL Connector/J, HikariCP, JUnit 5 + Mockito (+ optional Testcontainers).

---

## File structure (what changes where)

**Delete**
- `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`

**Modify**
- `server/src/main/java/com/exceltodb/config/AppConfig.java`
  - Remove `bulkLoadEnabled` and parsing of `import.bulkLoadEnabled`.
- `server/src/main/java/com/exceltodb/service/ImportService.java`
  - Remove bulk branching; always use JDBC.
  - Replace `convertValue()` guess-parse with type-driven conversion and strict error reporting.
- `server/src/main/java/com/exceltodb/config/DataSourceConfig.java`
  - Add `rewriteBatchedStatements=true` to JDBC URL (JDBC-only now).
- `server/src/main/resources/config.yaml` (if present in repo)
  - Remove `import.bulkLoadEnabled` if it exists.
- `client/` (only if UI exposes bulk toggle; search first)

**Create**
- `server/src/main/java/com/exceltodb/model/ColumnMeta.java`
- `server/src/main/java/com/exceltodb/service/ColumnConverter.java`
- `server/src/main/java/com/exceltodb/service/ColumnConverters.java`
- `server/src/main/java/com/exceltodb/service/ImportConversionException.java`

**Modify (metadata)**
- `server/src/main/java/com/exceltodb/service/DbService.java`
  - Add `getColumnMetas(databaseId, tableName)` used by importer.

**Tests**
- Create: `server/src/test/java/com/exceltodb/service/ColumnConvertersTest.java`
- Create: `server/src/test/java/com/exceltodb/service/ImportServiceStrictConversionTest.java`
- Delete or update: `server/src/test/java/com/exceltodb/service/ImportServiceRoutingTest.java` (bulk routing no longer applicable)
- Delete: `server/src/test/java/com/exceltodb/service/BulkLoadImportServiceIT.java` (bulk removed)

---

### Task 1: Remove bulk toggle from configuration

**Files:**
- Modify: `server/src/main/java/com/exceltodb/config/AppConfig.java`
- Modify (if present): `server/src/main/resources/config.yaml`

- [ ] **Step 1: Remove `bulkLoadEnabled` field and parsing**

In `AppConfig`:
- delete `private boolean bulkLoadEnabled = true;`
- delete the `import.bulkLoadEnabled` parsing block (`parseBoolean` can remain if still used elsewhere; if not, delete it too)
- ensure compilation still succeeds

- [ ] **Step 2: Remove `import.bulkLoadEnabled` from config YAML (if present)**

- [ ] **Step 3: Compile**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q -DskipTests package
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/config/AppConfig.java server/src/main/resources/config.yaml
git commit -m "refactor(server): remove bulk import toggle"
```

---

### Task 2: Ensure JDBC batch rewrite is enabled

**Files:**
- Modify: `server/src/main/java/com/exceltodb/config/DataSourceConfig.java`

- [ ] **Step 1: Append `rewriteBatchedStatements=true` to JDBC URL**

Target shape:

```java
config.setJdbcUrl(String.format(
  "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true",
  dbInfo.getHost(), dbInfo.getPort(), dbInfo.getDatabase()
));
```

Keep any other required params already present; remove `allowLoadLocalInfile=true` if it becomes unused after bulk removal.

- [ ] **Step 2: Compile**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q -DskipTests package
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/config/DataSourceConfig.java
git commit -m "perf(server): enable rewriteBatchedStatements for JDBC imports"
```

---

### Task 3: Add column metadata model + DB query

**Files:**
- Create: `server/src/main/java/com/exceltodb/model/ColumnMeta.java`
- Modify: `server/src/main/java/com/exceltodb/service/DbService.java`

- [ ] **Step 1: Create `ColumnMeta`**

```java
package com.exceltodb.model;

import lombok.Data;

@Data
public class ColumnMeta {
    private String name;          // COLUMN_NAME
    private String dataType;      // DATA_TYPE (lowercase)
    private String columnType;    // COLUMN_TYPE (e.g. decimal(10,2), tinyint(1))
    private boolean nullable;     // IS_NULLABLE == YES
    private Integer precision;    // NUMERIC_PRECISION (nullable)
    private Integer scale;        // NUMERIC_SCALE (nullable)
}
```

- [ ] **Step 2: Add `DbService.getColumnMetas(databaseId, tableName)`**

Add method (outline):

```java
public List<ColumnMeta> getColumnMetas(String databaseId, String tableName) {
    if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
    List<ColumnMeta> metas = new ArrayList<>();
    try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection()) {
        String sql =
            "SELECT COLUMN_NAME, LOWER(DATA_TYPE) AS DATA_TYPE_LOWER, LOWER(COLUMN_TYPE) AS COLUMN_TYPE_LOWER, " +
            "IS_NULLABLE, NUMERIC_PRECISION, NUMERIC_SCALE " +
            "FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
            "ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conn.getCatalog());
            ps.setString(2, tableName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta m = new ColumnMeta();
                    m.setName(rs.getString("COLUMN_NAME"));
                    m.setDataType(rs.getString("DATA_TYPE_LOWER"));
                    m.setColumnType(rs.getString("COLUMN_TYPE_LOWER"));
                    m.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    Object p = rs.getObject("NUMERIC_PRECISION");
                    Object s = rs.getObject("NUMERIC_SCALE");
                    m.setPrecision(p == null ? null : ((Number) p).intValue());
                    m.setScale(s == null ? null : ((Number) s).intValue());
                    metas.add(m);
                }
            }
        }
        return metas;
    } catch (SQLException e) {
        throw new RuntimeException("获取表列元数据失败: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 3: Unit compile**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q -DskipTests package
```

- [ ] **Step 4: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/model/ColumnMeta.java server/src/main/java/com/exceltodb/service/DbService.java
git commit -m "feat(server): expose table column metadata for import conversion"
```

---

### Task 4: Implement strict type-driven converters

**Files:**
- Create: `server/src/main/java/com/exceltodb/service/ImportConversionException.java`
- Create: `server/src/main/java/com/exceltodb/service/ColumnConverter.java`
- Create: `server/src/main/java/com/exceltodb/service/ColumnConverters.java`
- Create test: `server/src/test/java/com/exceltodb/service/ColumnConvertersTest.java`

- [ ] **Step 1: Create `ImportConversionException`**

```java
package com.exceltodb.service;

public class ImportConversionException extends RuntimeException {
    public ImportConversionException(String message) {
        super(message);
    }
    public ImportConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Create converter interface**

```java
package com.exceltodb.service;

import java.sql.PreparedStatement;

@FunctionalInterface
public interface ColumnConverter {
    void bind(PreparedStatement ps, int paramIndex, String raw, int rowIndex, int colIndex, String columnName);
}
```

- [ ] **Step 3: Create `ColumnConverters` factory**

Implement a static method:

```java
public static ColumnConverter forMySqlType(String dataTypeLower, String columnTypeLower, boolean nullable, Integer precision, Integer scale)
```

Minimum mapping:
- ints: `tinyint|smallint|int|bigint` → parse long (trim), allow empty -> null if nullable else error
- decimal: `decimal|numeric` → new BigDecimal(trim)
- text: `char|varchar|text|mediumtext|longtext` → raw (optionally trim? keep consistent with current behavior; prefer no-trim except empty check)
- datetime/timestamp/date: parse supported formats into `java.sql.Timestamp` / `java.sql.Date`
  - formats supported: `yyyy-MM-dd HH:mm:ss`, `yyyy/MM/dd HH:mm`, `yyyy-MM-dd`, ISO `yyyy-MM-ddTHH:mm:ss`
- fallback: throw `ImportConversionException("不支持的列类型: ...")`

**Strict error format** on failure:
`第 {rowIndex} 行，第 {colIndex} 列（{columnName}）解析失败：期望 {type}，实际值='{value}'`

- [ ] **Step 4: Unit tests for converters**

Create `ColumnConvertersTest` verifying:
- integer ok + overflow fails
- decimal ok + invalid fails
- datetime ok in 2-3 formats + invalid fails
- empty string behavior for nullable vs not-null

Run:

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q test -Dtest=ColumnConvertersTest
```

- [ ] **Step 5: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/service/ImportConversionException.java server/src/main/java/com/exceltodb/service/ColumnConverter.java server/src/main/java/com/exceltodb/service/ColumnConverters.java server/src/test/java/com/exceltodb/service/ColumnConvertersTest.java
git commit -m "feat(server): add strict column-type converters for JDBC import"
```

---

### Task 5: Refactor `ImportService` to use metadata-driven conversion (JDBC-only)

**Files:**
- Modify: `server/src/main/java/com/exceltodb/service/ImportService.java`
- Delete: `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java` (or in Task 6)

- [ ] **Step 1: Remove bulk branch**

Delete:

```java
if (appConfig.isBulkLoadEnabled()) { ... }
```

and any `BulkLoadImportService` injection/field.

- [ ] **Step 2: Before insert loop, fetch metas + build converters aligned to headers**

Outline:
- `List<ColumnMeta> metas = dbService.getColumnMetas(databaseId, tableNameBase);`
- Build `Map<String, ColumnMeta> lowerNameToMeta`
- For each `headers[i]`:
  - find meta by case-insensitive match
  - `converters[i] = ColumnConverters.forMySqlType(...)`

- [ ] **Step 3: Replace `convertValue(row[j])` with `converters[j].bind(...)`**

In the loop:

```java
for (int j = 0; j < headers.length; j++) {
    converters[j].bind(pstmt, j + 1, row[j], rowIndex, j + 1, headers[j]);
}
```

Wrap any thrown `ImportConversionException` into a `RuntimeException` so current rollback + message plumbing stays consistent.

- [ ] **Step 4: Keep conflict strategy SQL building, but ensure UPDATE does not overwrite PK columns if desired**

If current behavior updates all columns, keep it for compatibility. (Optimization can come later.)

- [ ] **Step 5: Run all tests**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q test
```

- [ ] **Step 6: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/main/java/com/exceltodb/service/ImportService.java
git commit -m "refactor(server): switch to JDBC-only import with typed conversion"
```

---

### Task 6: Delete bulk implementation + tests and fix build

**Files:**
- Delete: `server/src/main/java/com/exceltodb/service/BulkLoadImportService.java`
- Delete: `server/src/test/java/com/exceltodb/service/BulkLoadImportServiceIT.java`
- Delete or rewrite: `server/src/test/java/com/exceltodb/service/ImportServiceRoutingTest.java`

- [ ] **Step 1: Delete bulk files**
- [ ] **Step 2: Update or delete routing test**
  - Replace with a simpler `ImportService` test that asserts it calls `DataSource.getConnection()` and proceeds (no bulk branch).
- [ ] **Step 3: Run tests**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q test
```

- [ ] **Step 4: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add -A
git commit -m "chore(server): remove bulk-load import path"
```

---

### Task 7: Add strict conversion regression test in `ImportService`

**Files:**
- Create: `server/src/test/java/com/exceltodb/service/ImportServiceStrictConversionTest.java`

Test idea (Mockito):
- mock `DataSourceConfig.getDataSource` and connection/statement interactions OR use Testcontainers MySQL.
- Easiest stable test: mock `DbService.getColumnMetas` to return e.g. a `datetime` column; feed CSV row with invalid datetime; assert returned `ImportResult.message` contains `第 1 行，第 1 列（colName）解析失败`.

- [ ] **Step 1: Write test**
- [ ] **Step 2: Run**

```powershell
Set-Location D:\CodeProject\exceltodb\server
mvn -q test -Dtest=ImportServiceStrictConversionTest
```

- [ ] **Step 3: Commit**

```powershell
Set-Location D:\CodeProject\exceltodb
git add server/src/test/java/com/exceltodb/service/ImportServiceStrictConversionTest.java
git commit -m "test(server): strict row/column error reporting for JDBC import"
```

---

## Self-review (spec coverage)

- Remove bulk toggle + service: Tasks 1, 5, 6
- JDBC batch tuning: Task 2
- Column-type-driven conversion: Tasks 3, 4, 5
- Strict row/col error messages: Tasks 4, 7
- Keep import strategy semantics: Task 5

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-22-jdbc-only-import-optimization.md`.

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks. **REQUIRED SUB-SKILL:** `superpowers:subagent-driven-development`
2. **Inline Execution** — execute tasks in this session. **REQUIRED SUB-SKILL:** `superpowers:executing-plans`

Which approach?

