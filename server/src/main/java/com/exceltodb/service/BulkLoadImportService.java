package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ImportStage;
import com.opencsv.CSVReader;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BulkLoadImportService {
    private final AppConfig appConfig;
    private final ExcelParserService excelParserService;
    private final ImportHeartbeatStore heartbeatStore;
    private final DbService dbService;

    public BulkLoadImportService(AppConfig appConfig, ExcelParserService excelParserService, ImportHeartbeatStore heartbeatStore, DbService dbService) {
        this.appConfig = appConfig;
        this.excelParserService = excelParserService;
        this.heartbeatStore = heartbeatStore;
        this.dbService = dbService;
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
        String baseTableName = baseTableName(targetTable);

        long processedRows = 0;
        ImportResult result = new ImportResult();
        ImportStage lastStage = ImportStage.READING;

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                validateQualifiedTablePrefixMatchesCatalog(conn, targetTable);

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
                List<String> headerColumns = readStandardCsvHeaderColumns(standardCsvPath);
                headerColumns = validateAndNormalizeHeaderColumns(headerColumns);
                headerColumns = validateHeaderColumnsAgainstTable(request.getDatabaseId(), baseTableName, headerColumns);

                createStagingTable(conn, tmpTable, targetTable);

                lastStage = ImportStage.INSERTING;
                heartbeatUpdate(requestId, lastStage, 0, "LOAD DATA");
                loadDataLocal(conn, tmpTable, standardCsvPath, headerColumns);

                processedRows = countRows(conn, tmpTable);
                if (processedRows <= 0) {
                    throw new RuntimeException(
                            "Bulk load 未写入任何行：当前数据库可能禁用 LOAD DATA LOCAL INFILE（常见错误 1148），或 CSV 未解析出数据行。请改用 JDBC 导入或联系 DBA 开启 LOCAL INFILE。");
                }
                lastStage = ImportStage.INSERTING;
                heartbeatUpdate(requestId, lastStage, processedRows, "MERGE");
                mergeIntoTarget(conn, tmpTable, targetTable, request, headerColumns, baseTableName);

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

    private void loadDataLocal(Connection conn, String tmp, Path csv, List<String> headerColumns) throws Exception {
        if (headerColumns == null || headerColumns.isEmpty()) {
            throw new IllegalArgumentException("headerColumns must not be empty");
        }
        Path standardCsvPath = validateAndNormalizeInfilePath(csv);
        // MySQL accepts forward slashes on Windows; avoids needing backslash escaping in string literals.
        String infilePath = standardCsvPath.toString().replace('\\', '/');

        List<String> userVars = new ArrayList<>(headerColumns.size());
        for (int i = 1; i <= headerColumns.size(); i++) {
            userVars.add("@v" + i);
        }
        StringBuilder setClause = new StringBuilder();
        for (int i = 0; i < headerColumns.size(); i++) {
            if (i > 0) setClause.append(", ");
            String col = quoteIdentPart(headerColumns.get(i));
            String v = userVars.get(i);
            setClause.append(col).append("=NULLIF(").append(v).append(", '')");
        }

        String sql = "LOAD DATA LOCAL INFILE " + quoteSqlStringLiteral(infilePath) + " INTO TABLE " + quoteQualifiedIdent(tmp) + " " +
                "CHARACTER SET utf8mb4 " +
                "FIELDS TERMINATED BY ',' ENCLOSED BY '\"' ESCAPED BY '\"' " +
                "LINES TERMINATED BY '\\n' " +
                "IGNORE 1 LINES " +
                "(" + String.join(",", userVars) + ") " +
                "SET " + setClause;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    /**
     * Minimal placeholder merge. Task 5 will replace with explicit columns and conflict strategies.
     */
    private void mergeIntoTarget(Connection conn, String tmp, String target, ImportRequest request, List<String> headerColumns, String baseTableName) throws Exception {
        if (headerColumns == null || headerColumns.isEmpty()) {
            throw new IllegalArgumentException("headerColumns must not be empty");
        }

        String importMode = request == null ? null : request.getImportMode();
        String conflictStrategy = request == null ? null : request.getConflictStrategy();
        if ("TRUNCATE".equals(importMode)) {
            conflictStrategy = "ERROR";
        }

        List<String> pkCols = List.of();
        if (request != null && request.getDatabaseId() != null && !request.getDatabaseId().isBlank()) {
            pkCols = dbService.getPrimaryKeyColumns(request.getDatabaseId(), baseTableName);
        }
        Set<String> pkLower = new HashSet<>();
        for (String pk : pkCols) {
            if (pk != null && !pk.isBlank()) {
                pkLower.add(pk.trim().toLowerCase(Locale.ROOT));
            }
        }

        String colsSql = joinQuotedIdents(headerColumns);
        String srcAlias = "src";
        String selectColsSql = joinQualifiedSelectCols(headerColumns, tmp, srcAlias);

        try (Statement st = conn.createStatement()) {
            if ("IGNORE".equals(conflictStrategy)) {
                st.execute("INSERT IGNORE INTO " + quoteQualifiedIdent(target) + " (" + colsSql + ") " +
                        "SELECT " + selectColsSql + " FROM " + quoteQualifiedIdent(tmp) + " AS " + quoteIdentPart(srcAlias));
                return;
            }
            if ("UPDATE".equals(conflictStrategy)) {
                String updateClause = buildOnDuplicateKeyUpdateClause(headerColumns, pkLower, srcAlias);
                st.execute("INSERT INTO " + quoteQualifiedIdent(target) + " (" + colsSql + ") " +
                        "SELECT " + selectColsSql + " FROM " + quoteQualifiedIdent(tmp) + " AS " + quoteIdentPart(srcAlias) + " " +
                        "ON DUPLICATE KEY UPDATE " + updateClause);
                return;
            }

            st.execute("INSERT INTO " + quoteQualifiedIdent(target) + " (" + colsSql + ") " +
                    "SELECT " + selectColsSql + " FROM " + quoteQualifiedIdent(tmp) + " AS " + quoteIdentPart(srcAlias));
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
            return quoteIdentPart(parts[0].trim());
        }
        if (parts.length == 2) {
            return quoteIdentPart(parts[0].trim()) + "." + quoteIdentPart(parts[1].trim());
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

    private static String baseTableName(String qualifiedTable) {
        if (qualifiedTable == null || qualifiedTable.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        String[] parts = qualifiedTable.split("\\.");
        if (parts.length == 1) return parts[0].trim();
        if (parts.length == 2) return parts[1].trim();
        throw new IllegalArgumentException("Invalid qualified identifier (expected [table] or [db.table]): " + qualifiedTable);
    }

    private static void validateQualifiedTablePrefixMatchesCatalog(Connection conn, String qualifiedTable) throws Exception {
        if (qualifiedTable == null || qualifiedTable.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        String[] parts = qualifiedTable.split("\\.");
        if (parts.length == 2) {
            String dbPrefix = parts[0].trim();
            String catalog = conn.getCatalog();
            if (catalog != null && !catalog.isBlank() && !catalog.equalsIgnoreCase(dbPrefix)) {
                throw new IllegalArgumentException("tableName schema prefix does not match connection catalog: " + dbPrefix + " != " + catalog);
            }
        } else if (parts.length != 1) {
            throw new IllegalArgumentException("Invalid qualified identifier (expected [table] or [db.table]): " + qualifiedTable);
        }
    }

    private List<String> readStandardCsvHeaderColumns(Path standardCsvPath) throws Exception {
        Path normalized = validateAndNormalizeInfilePath(standardCsvPath);
        try (Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8);
             CSVReader csvReader = new CSVReader(reader)) {
            String[] header = csvReader.readNext();
            if (header == null || header.length == 0) {
                throw new IllegalArgumentException("CSV header is empty");
            }
            List<String> cols = new ArrayList<>(header.length);
            for (String h : header) {
                String c = h == null ? "" : h.trim();
                if (c.isEmpty()) {
                    throw new IllegalArgumentException("CSV header contains empty column name");
                }
                cols.add(c);
            }
            return cols;
        }
    }

    private static List<String> validateAndNormalizeHeaderColumns(List<String> headerColumns) {
        if (headerColumns == null || headerColumns.isEmpty()) {
            throw new IllegalArgumentException("CSV header is empty");
        }

        List<String> trimmed = new ArrayList<>(headerColumns.size());
        Set<String> seenLower = new HashSet<>();
        for (String raw : headerColumns) {
            String c = raw == null ? "" : raw.trim();
            if (c.isEmpty()) {
                throw new IllegalArgumentException("CSV header contains empty column name");
            }
            String lower = c.toLowerCase(Locale.ROOT);
            if (!seenLower.add(lower)) {
                throw new IllegalArgumentException("CSV header contains duplicate column (case-insensitive): " + c);
            }
            trimmed.add(c);
        }
        return trimmed;
    }

    private List<String> validateHeaderColumnsAgainstTable(String databaseId, String baseTableName, List<String> headerColumns) {
        List<String> tableColumns = dbService.getOrderedColumnNames(databaseId, baseTableName);
        if (tableColumns == null || tableColumns.isEmpty()) {
            throw new IllegalArgumentException("Target table not found or no access: " + baseTableName);
        }

        Map<String, String> lowerToActual = new HashMap<>(tableColumns.size());
        for (String c : tableColumns) {
            if (c == null) continue;
            lowerToActual.put(c.toLowerCase(Locale.ROOT), c);
        }

        List<String> canonicalHeaderCols = new ArrayList<>(headerColumns.size());
        for (String col : headerColumns) {
            String actual = lowerToActual.get(col.toLowerCase(Locale.ROOT));
            if (actual == null) {
                throw new IllegalArgumentException("CSV header column not found in target table: " + col);
            }
            canonicalHeaderCols.add(actual);
        }
        return canonicalHeaderCols;
    }

    private static String joinQuotedIdents(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoteIdentPart(cols.get(i)));
        }
        return sb.toString();
    }

    private static String joinQualifiedSelectCols(List<String> cols, String fromTable) {
        return joinQualifiedSelectCols(cols, fromTable, null);
    }

    private static String joinQualifiedSelectCols(List<String> cols, String fromTable, String alias) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            if (alias == null || alias.isBlank()) {
                sb.append(quoteQualifiedIdent(fromTable));
            } else {
                sb.append(quoteIdentPart(alias));
            }
            sb.append(".").append(quoteIdentPart(cols.get(i)));
        }
        return sb.toString();
    }

    private static String buildOnDuplicateKeyUpdateClause(List<String> headerColumns, Set<String> primaryKeysLower, String sourceAlias) {
        Set<String> pkLower = primaryKeysLower == null ? Set.of() : primaryKeysLower;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String c : headerColumns) {
            if (c != null && pkLower.contains(c.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!first) sb.append(", ");
            first = false;
            String qc = quoteIdentPart(c);
            sb.append(qc).append("=").append(quoteIdentPart(sourceAlias)).append(".").append(qc);
        }
        if (first) {
            // no-op update to satisfy MySQL syntax; primary key was the only imported column
            String qcPk = quoteIdentPart(headerColumns.get(0));
            return qcPk + "=" + qcPk;
        }
        return sb.toString();
    }
}

