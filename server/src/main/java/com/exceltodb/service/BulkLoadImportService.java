package com.exceltodb.service;

import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ImportStage;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
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

    /**
     * Bulk import via: (optional TRUNCATE) -> staging table -> LOAD DATA LOCAL INFILE -> naive merge -> drop staging -> commit.
     */
    public ImportResult importWithLoadData(DataSource ds, ImportRequest request) {
        String requestId = request == null ? null : request.getRequestId();
        String targetTable = request == null ? null : request.getTableName();
        String tmpTable = "__import_" + safeId(requestId);

        long processedRows = 0;
        ImportResult result = new ImportResult();

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if ("TRUNCATE".equals(request.getImportMode())) {
                    heartbeatUpdate(requestId, ImportStage.TRUNCATE, 0, "");
                    try (Statement st = conn.createStatement()) {
                        st.execute("TRUNCATE TABLE " + quoteIdent(targetTable));
                    }
                }

                heartbeatUpdate(requestId, ImportStage.READING, 0, "");
                Path standardCsvPath = ensureStandardCsv(request);

                createStagingTable(conn, tmpTable, targetTable);

                heartbeatUpdate(requestId, ImportStage.INSERTING, 0, "LOAD DATA");
                loadDataLocal(conn, tmpTable, standardCsvPath);

                processedRows = countRows(conn, tmpTable);
                heartbeatUpdate(requestId, ImportStage.INSERTING, processedRows, "MERGE");
                mergeIntoTarget(conn, tmpTable, targetTable);

                dropTableQuietly(conn, tmpTable);

                heartbeatUpdate(requestId, ImportStage.COMMITTING, processedRows, "");
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

                heartbeatStoreError(requestId, processedRows, e.getMessage());
                result.setSuccess(false);
                result.setImportedRows(safeInt(processedRows));
                result.setFailedRows(0);
                result.setMessage("导入失败: " + (e.getMessage() == null ? "" : e.getMessage()));
                return result;
            }
        } catch (Exception e) {
            heartbeatStoreError(requestId, processedRows, e.getMessage());
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
            st.execute("DROP TABLE IF EXISTS " + quoteIdent(tmp));
            st.execute("CREATE TABLE " + quoteIdent(tmp) + " LIKE " + quoteIdent(target));
        }
    }

    private void loadDataLocal(Connection conn, String tmp, Path csv) throws Exception {
        String sql = "LOAD DATA LOCAL INFILE ? INTO TABLE " + quoteIdent(tmp) + " " +
                "CHARACTER SET utf8mb4 " +
                "FIELDS TERMINATED BY ',' ENCLOSED BY '\"' ESCAPED BY '\"' " +
                "LINES TERMINATED BY '\\n' " +
                "IGNORE 1 LINES";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, csv.toAbsolutePath().toString());
            ps.execute();
        }
    }

    /**
     * Minimal placeholder merge. Task 5 will replace with explicit columns and conflict strategies.
     */
    private void mergeIntoTarget(Connection conn, String tmp, String target) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO " + quoteIdent(target) + " SELECT * FROM " + quoteIdent(tmp));
        }
    }

    private long countRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + quoteIdent(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void dropTableQuietly(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + quoteIdent(table));
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

    private static String safeId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return "no_request";
        }
        return requestId.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String quoteIdent(String ident) {
        if (ident == null || ident.isBlank()) {
            throw new IllegalArgumentException("Identifier is blank");
        }
        return "`" + ident.replace("`", "``") + "`";
    }

    private static int safeInt(long v) {
        if (v <= 0) return 0;
        if (v >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }
}

