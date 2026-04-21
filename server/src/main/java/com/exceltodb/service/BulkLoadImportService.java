package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ImportStage;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.security.MessageDigest;

@Service
public class BulkLoadImportService {
    private final AppConfig appConfig;
    private final ExcelParserService excelParserService;
    private final ImportHeartbeatStore heartbeatStore;

    public BulkLoadImportService(AppConfig appConfig, ExcelParserService excelParserService, ImportHeartbeatStore heartbeatStore) {
        this.appConfig = appConfig;
        this.excelParserService = excelParserService;
        this.heartbeatStore = heartbeatStore;
    }

    /**
     * Bulk import via: (optional TRUNCATE) -> staging table -> LOAD DATA LOCAL INFILE -> naive merge -> drop staging.
     */
    public ImportResult importWithLoadData(DataSource ds, ImportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ImportRequest must not be null");
        }
        requireNonBlank(request.getFilename(), "filename");
        requireNonBlank(request.getTableName(), "tableName");
        requireNonBlank(request.getDatabaseId(), "databaseId");

        String requestId = request.getRequestId();
        String targetTable = request.getTableName();
        String tmpTable = tmpStagingTableName(requestId);

        long processedRows = 0;
        ImportResult result = new ImportResult();
        ImportStage lastStage = ImportStage.READING;

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if ("TRUNCATE".equals(request.getImportMode())) {
                    lastStage = ImportStage.TRUNCATE;
                    heartbeatUpdate(requestId, lastStage, 0, "");
                    try (Statement st = conn.createStatement()) {
                        st.execute("TRUNCATE TABLE " + quoteQualifiedIdent(targetTable));
                    }
                }

                lastStage = ImportStage.READING;
                heartbeatUpdate(requestId, lastStage, 0, "");
                Path standardCsvPath = ensureStandardCsv(request);

                createStagingTable(conn, tmpTable, targetTable);

                lastStage = ImportStage.INSERTING;
                heartbeatUpdate(requestId, lastStage, 0, "LOAD DATA");
                loadDataLocal(conn, tmpTable, standardCsvPath);

                processedRows = countRows(conn, tmpTable);
                lastStage = ImportStage.INSERTING;
                heartbeatUpdate(requestId, lastStage, processedRows, "MERGE");
                mergeIntoTarget(conn, tmpTable, targetTable);

                dropTableQuietly(conn, tmpTable);

                lastStage = ImportStage.COMMITTING;
                heartbeatUpdate(requestId, lastStage, processedRows, "");
                conn.commit();

                result.setSuccess(true);
                result.setImportedRows(safeInt(processedRows));
                result.setFailedRows(0);
                result.setMessage("导入成功");
                heartbeatStoreSuccess(requestId, processedRows);
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception ignored) {
                    // best-effort rollback only
                }
                dropTableQuietly(conn, tmpTable);

                String errorMessage = e.getMessage();
                heartbeatUpdate(requestId, lastStage, processedRows, "ERROR@" + lastStage + ": " + (errorMessage == null ? "" : errorMessage));
                heartbeatStoreError(requestId, processedRows, "ERROR@" + lastStage + ": " + (errorMessage == null ? "" : errorMessage));
                result.setSuccess(false);
                result.setImportedRows(safeInt(processedRows));
                result.setFailedRows(0);
                result.setMessage("导入失败: " + (e.getMessage() == null ? "" : e.getMessage()));
                return result;
            }
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            heartbeatUpdate(requestId, lastStage, processedRows, "ERROR@" + lastStage + ": " + (errorMessage == null ? "" : errorMessage));
            heartbeatStoreError(requestId, processedRows, "ERROR@" + lastStage + ": " + (errorMessage == null ? "" : errorMessage));
            result.setSuccess(false);
            result.setImportedRows(safeInt(processedRows));
            result.setFailedRows(0);
            result.setMessage("导入失败: " + (e.getMessage() == null ? "" : e.getMessage()));
            return result;
        }
    }

    private Path ensureStandardCsv(ImportRequest request) throws Exception {
        int sheetIndex = request.getSheetIndex() == null ? 0 : request.getSheetIndex();
        try {
            return excelParserService.ensureStandardCsv(request.getFilename(), sheetIndex);
        } catch (InvalidFormatException e) {
            throw e;
        }
    }

    private void createStagingTable(Connection conn, String tmp, String target) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + quoteQualifiedIdent(tmp));
            st.execute("CREATE TABLE " + quoteQualifiedIdent(tmp) + " LIKE " + quoteQualifiedIdent(target));
        }
    }

    private void loadDataLocal(Connection conn, String tmp, Path csv) throws Exception {
        Path standardCsvPath = validateAndNormalizeInfilePath(csv);
        // MySQL accepts forward slashes on Windows; avoids needing backslash escaping in string literals.
        String infilePath = standardCsvPath.toString().replace('\\', '/');
        String sql = "LOAD DATA LOCAL INFILE " + quoteSqlStringLiteral(infilePath) + " INTO TABLE " + quoteQualifiedIdent(tmp) + " " +
                "CHARACTER SET utf8mb4 " +
                "FIELDS TERMINATED BY ',' ENCLOSED BY '\"' ESCAPED BY '\"' " +
                "LINES TERMINATED BY '\\n' " +
                "IGNORE 1 LINES";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    /**
     * Minimal placeholder merge. Task 5 will replace with explicit columns and conflict strategies.
     */
    private void mergeIntoTarget(Connection conn, String tmp, String target) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO " + quoteQualifiedIdent(target) + " SELECT * FROM " + quoteQualifiedIdent(tmp));
        }
    }

    private long countRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + quoteQualifiedIdent(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void dropTableQuietly(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + quoteQualifiedIdent(table));
        } catch (Exception ignored) {
            // best-effort cleanup only
        }
    }

    private void heartbeatUpdate(String requestId, ImportStage stage, long processedRows, String message) {
        if (requestId == null || requestId.isBlank()) return;
        heartbeatStore.update(requestId, stage, processedRows, message);
    }

    private void heartbeatStoreSuccess(String requestId, long processedRows) {
        if (requestId == null || requestId.isBlank()) return;
        heartbeatStore.success(requestId, processedRows);
    }

    private void heartbeatStoreError(String requestId, long processedRows, String message) {
        if (requestId == null || requestId.isBlank()) return;
        heartbeatStore.error(requestId, processedRows, message);
    }

    private static String tmpStagingTableName(String requestId) {
        String prefix = "__import_";
        String hash10 = shortSha256Hex(requestId == null ? "no_request" : requestId).substring(0, 10);
        String name = prefix + hash10;
        if (name.length() > 64) {
            // defensive: should never happen, but keep MySQL identifier max length invariant
            return name.substring(0, 64);
        }
        return name;
    }

    private static String shortSha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash requestId", e);
        }
    }

    private static String quoteQualifiedIdent(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Identifier is blank");
        }
        String[] parts = name.split("\\.");
        if (parts.length == 1) {
            return quoteIdentPart(parts[0]);
        }
        if (parts.length == 2) {
            return quoteIdentPart(parts[0]) + "." + quoteIdentPart(parts[1]);
        }
        throw new IllegalArgumentException("Invalid qualified identifier (expected [table] or [db.table]): " + name);
    }

    private static String quoteIdentPart(String part) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException("Identifier part is blank");
        }
        return "`" + part.replace("`", "``") + "`";
    }

    private static String quoteSqlStringLiteral(String s) {
        if (s == null) {
            return "NULL";
        }
        // Use SQL-standard single-quote doubling.
        String escaped = s.replace("'", "''");
        return "'" + escaped + "'";
    }

    private Path validateAndNormalizeInfilePath(Path standardCsvPath) {
        if (standardCsvPath == null) {
            throw new IllegalArgumentException("standardCsvPath must not be null");
        }

        Path allowedBaseDir = Paths.get(appConfig.getUploadTempPath()).toAbsolutePath().normalize();
        Path normalized = standardCsvPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(allowedBaseDir)) {
            throw new IllegalArgumentException("standardCsvPath is outside allowed base directory");
        }

        String normalizedPathStr = normalized.toString();
        if (containsAny(normalizedPathStr, '\0', '\n', '\r')) {
            throw new IllegalArgumentException("standardCsvPath contains illegal characters");
        }
        return normalized;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static boolean containsAny(String s, char... chars) {
        if (s == null || s.isEmpty() || chars == null || chars.length == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            for (char t : chars) {
                if (c == t) return true;
            }
        }
        return false;
    }

    private static int safeInt(long v) {
        if (v <= 0) return 0;
        if (v >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }
}

