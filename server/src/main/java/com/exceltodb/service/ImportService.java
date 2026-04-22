package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.CreateTableRequest;
import com.exceltodb.model.ColumnMeta;
import com.exceltodb.model.ImportStage;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.TableInfo;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

@Service
public class ImportService {

    private final DataSourceConfig dataSourceConfig;
    private final ExcelParserService excelParserService;
    private final DbService dbService;
    private final AppConfig appConfig;
    private final ImportHeartbeatStore heartbeatStore;

    public ImportService(DataSourceConfig dataSourceConfig, ExcelParserService excelParserService,
                        DbService dbService, AppConfig appConfig, ImportHeartbeatStore heartbeatStore) {
        this.dataSourceConfig = dataSourceConfig;
        this.excelParserService = excelParserService;
        this.dbService = dbService;
        this.appConfig = appConfig;
        this.heartbeatStore = heartbeatStore;
    }

    public ImportResult importData(ImportRequest request) throws IOException, InvalidFormatException {
        ImportResult result = new ImportResult();

        DataSource ds = dataSourceConfig.getDataSource(request.getDatabaseId());
        String requestId = request.getRequestId();
        if (requestId != null && !requestId.isBlank()) {
            heartbeatStore.start(requestId);
        }

        long lastProcessedRows = 0;
        long lastHeartbeatMs = System.currentTimeMillis();

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Handle truncate mode
                if ("TRUNCATE".equals(request.getImportMode())) {
                    if (requestId != null && !requestId.isBlank()) {
                        heartbeatStore.update(requestId, ImportStage.TRUNCATE, 0, "");
                    }
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("TRUNCATE TABLE `" + request.getTableName() + "`");
                    }
                }

                String filename = request.getFilename();
                if (filename == null || filename.isBlank()) {
                    throw new RuntimeException("文件名为空");
                }
                int sheetIndex = request.getSheetIndex() != null ? request.getSheetIndex() : 0;

                String[] headers;
                Iterable<String[]> rowsIterable;
                boolean isCsv = filename.toLowerCase().endsWith(".csv");

                CsvStream csvStream = null;
                if (isCsv) {
                    if (requestId != null && !requestId.isBlank()) {
                        heartbeatStore.update(requestId, ImportStage.READING, 0, "");
                    }
                    // Stream CSV to avoid reading all rows into memory
                    csvStream = openCsvStream(filename);
                    headers = csvStream.header;
                    rowsIterable = csvStream;
                } else {
                    if (requestId != null && !requestId.isBlank()) {
                        heartbeatStore.update(requestId, ImportStage.READING, 0, "");
                    }
                    // Excel path: keep existing reader for now (POI streaming refactor can be done later)
                    List<String[]> allData = excelParserService.readAllData(filename, sheetIndex);
                    if (allData.isEmpty()) {
                        throw new RuntimeException("文件没有数据");
                    }
                    headers = allData.get(0);
                    rowsIterable = allData.subList(1, allData.size());
                }

                // Determine conflict strategy
                String conflictAction = getConflictAction(request, headers);

                // Build converters aligned to headers (strict type-driven conversion)
                String baseTableName = baseTableName(request.getTableName());
                List<ColumnMeta> metas = dbService.getColumnMetas(request.getDatabaseId(), baseTableName);
                Map<String, ColumnMeta> metaByLower = new HashMap<>(metas.size() * 2);
                for (ColumnMeta m : metas) {
                    if (m != null && m.getName() != null) {
                        metaByLower.put(m.getName().toLowerCase(Locale.ROOT), m);
                    }
                }

                ColumnConverter[] converters = new ColumnConverter[headers.length];
                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i] == null ? "" : headers[i].trim();
                    if (h.isEmpty()) {
                        throw new RuntimeException("表头包含空列名（第 " + (i + 1) + " 列）");
                    }
                    ColumnMeta m = metaByLower.get(h.toLowerCase(Locale.ROOT));
                    if (m == null) {
                        throw new RuntimeException("表头列在目标表中不存在: " + h);
                    }
                    converters[i] = ColumnConverters.forMySqlType(
                            m.getDataType(),
                            m.getColumnType(),
                            m.isNullable(),
                            m.getPrecision(),
                            m.getScale()
                    );
                    // Canonicalize header to actual column name to preserve case
                    headers[i] = m.getName();
                }

                // Build INSERT SQL
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("INSERT ");
                if ("IGNORE".equals(request.getConflictStrategy())) {
                    sqlBuilder.append("IGNORE ");
                }
                sqlBuilder.append("INTO `").append(request.getTableName()).append("` (");
                for (int i = 0; i < headers.length; i++) {
                    sqlBuilder.append("`").append(headers[i]).append("`");
                    if (i < headers.length - 1) sqlBuilder.append(", ");
                }
                sqlBuilder.append(") VALUES (");
                for (int i = 0; i < headers.length; i++) {
                    sqlBuilder.append("?");
                    if (i < headers.length - 1) sqlBuilder.append(", ");
                }
                sqlBuilder.append(")");
                sqlBuilder.append(conflictAction);

                String sql = sqlBuilder.toString();

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    int importedRows = 0;
                    int batchSize = Math.max(1, appConfig.getBatchSize());
                    int batchCount = 0;
                    int rowIndex = 0;

                    for (String[] row : rowsIterable) {
                        rowIndex++;
                        lastProcessedRows = rowIndex;
                        if (requestId != null && !requestId.isBlank()) {
                            long now = System.currentTimeMillis();
                            if ((rowIndex % 500 == 0) || (now - lastHeartbeatMs > 10_000)) {
                                heartbeatStore.update(requestId, ImportStage.INSERTING, rowIndex, "");
                                lastHeartbeatMs = now;
                            }
                        }

                        // Skip if row has fewer columns than headers
                        if (row.length < headers.length) {
                            continue;
                        }

                        for (int j = 0; j < headers.length; j++) {
                            try {
                                converters[j].bind(pstmt, j + 1, row[j], rowIndex, j + 1, headers[j]);
                            } catch (ImportConversionException e) {
                                throw new RuntimeException(e.getMessage(), e);
                            }
                        }

                        try {
                            pstmt.addBatch();
                            batchCount++;
                            if (batchCount >= batchSize) {
                                importedRows += executeAndCountBatch(pstmt);
                                batchCount = 0;
                                if (requestId != null && !requestId.isBlank()) {
                                    long now = System.currentTimeMillis();
                                    if (now - lastHeartbeatMs > 2_000) {
                                        heartbeatStore.update(requestId, ImportStage.INSERTING, rowIndex, "");
                                        lastHeartbeatMs = now;
                                    }
                                }
                            }
                        } catch (SQLException e) {
                            // Primary key conflict or other SQL error
                            // Rollback entire transaction on first error for ERROR strategy
                            if ("ERROR".equals(request.getConflictStrategy())) {
                                throw new RuntimeException("第 " + rowIndex + " 行导入失败: " + e.getMessage(), e);
                            }
                            // For UPSERT/IGNORE, errors are already handled by the SQL clause
                        }
                    }

                    if (batchCount > 0) {
                        importedRows += executeAndCountBatch(pstmt);
                    }

                    result.setImportedRows(importedRows);
                    if ("TRUNCATE".equals(request.getImportMode()) && importedRows == 0) {
                        result.setSuccess(false);
                        result.setMessage(
                                "导入完成但未写入任何数据行：目标表已按 TRUNCATE 清空，但本次未写入任何行。请检查数据文件与列映射。");
                    } else {
                        result.setSuccess(true);
                        result.setMessage("导入成功");
                    }

                    if (requestId != null && !requestId.isBlank()) {
                        heartbeatStore.update(requestId, ImportStage.COMMITTING, rowIndex, "");
                    }
                } finally {
                    if (csvStream != null) {
                        try { csvStream.close(); } catch (Exception ignored) {}
                    }
                }

                conn.commit();
                if (requestId != null && !requestId.isBlank()) {
                    if (result.isSuccess()) {
                        heartbeatStore.success(requestId, lastProcessedRows);
                    } else {
                        heartbeatStore.error(requestId, result.getImportedRows(), result.getMessage());
                    }
                }

            } catch (Exception e) {
                conn.rollback();
                if (requestId != null && !requestId.isBlank()) {
                    heartbeatStore.error(requestId, lastProcessedRows, e.getMessage());
                }
                throw e;
            }

        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("导入失败: " + e.getMessage());
        }

        return result;
    }

    private int executeAndCountBatch(PreparedStatement pstmt) throws SQLException {
        int[] counts = pstmt.executeBatch();
        int imported = 0;
        for (int c : counts) {
            if (c == Statement.SUCCESS_NO_INFO) {
                imported += 1;
            } else if (c >= 1) {
                imported += 1;
            }
        }
        return imported;
    }

    private CsvStream openCsvStream(String filename) throws IOException {
        Path path = excelParserService.getFilePath(filename);
        if (path == null) {
            throw new RuntimeException("文件不存在: " + filename);
        }
        try {
            CSVReader reader = excelParserService.createCsvReader(path);
            String[] header = reader.readNext();
            if (header == null) {
                try { reader.close(); } catch (Exception ignored) {}
                throw new RuntimeException("文件没有数据");
            }
            // Reuse BOM stripping logic from parser by calling readAllData would defeat streaming;
            // simplest: strip UTF-8 BOM if present.
            if (header.length > 0 && header[0] != null && !header[0].isEmpty() && header[0].charAt(0) == '\uFEFF') {
                header[0] = header[0].substring(1);
            }
            return new CsvStream(reader, header);
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    private static class CsvStream implements Iterable<String[]>, AutoCloseable {
        private final CSVReader reader;
        private final String[] header;
        private boolean closed = false;

        private CsvStream(CSVReader reader, String[] header) {
            this.reader = reader;
            this.header = header;
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            reader.close();
        }

        @Override
        public Iterator<String[]> iterator() {
            return new Iterator<>() {
                String[] next = readNext();

                private String[] readNext() {
                    try {
                        return reader.readNext();
                    } catch (Exception e) {
                        try { close(); } catch (Exception ignored) {}
                        throw new RuntimeException("CSV读取失败: " + e.getMessage(), e);
                    }
                }

                @Override
                public boolean hasNext() {
                    if (next == null) {
                        try { close(); } catch (Exception ignored) {}
                        return false;
                    }
                    return true;
                }

                @Override
                public String[] next() {
                    if (next == null) throw new NoSuchElementException();
                    String[] cur = next;
                    next = readNext();
                    return cur;
                }
            };
        }
    }

    private String getConflictAction(ImportRequest request, String[] headers) {
        String strategy = request.getConflictStrategy();

        if ("UPDATE".equals(strategy)) {
            // Build ON DUPLICATE KEY UPDATE clause with all columns
            StringBuilder sb = new StringBuilder(" ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < headers.length; i++) {
                sb.append("`").append(headers[i]).append("`=VALUES(`").append(headers[i]).append("`)");
                if (i < headers.length - 1) sb.append(", ");
            }
            return sb.toString();
        } else if ("IGNORE".equals(strategy)) {
            return "";
        } else {
            return "";
        }
    }

    private static String baseTableName(String qualifiedTable) {
        if (qualifiedTable == null || qualifiedTable.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        String[] parts = qualifiedTable.split("\\.");
        if (parts.length == 1) return parts[0].trim();
        if (parts.length == 2) return parts[1].trim();
        throw new IllegalArgumentException("Invalid table identifier: " + qualifiedTable);
    }

    public String createTable(CreateTableRequest request) throws IOException, InvalidFormatException {
        DataSource ds = dataSourceConfig.getDataSource(request.getDatabaseId());
        int sheetIndex = request.getSheetIndex() != null ? request.getSheetIndex() : 0;

        try (Connection conn = ds.getConnection()) {
            // Analyze Excel file to get column info
            List<String[]> allData = excelParserService.readAllData(request.getFilename(), sheetIndex);
            if (allData.isEmpty()) {
                throw new RuntimeException("文件没有数据");
            }

            String[] headers = allData.get(0);
            List<String[]> sampleData = allData.subList(1, Math.min(101, allData.size()));

            // Build CREATE TABLE SQL
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("CREATE TABLE `").append(request.getTableName()).append("` (");

            for (int i = 0; i < headers.length; i++) {
                String columnName = headers[i];
                String inferredType = inferColumnType(sampleData, i);

                sqlBuilder.append("`").append(columnName).append("` ").append(inferredType);

                if (i < headers.length - 1) {
                    sqlBuilder.append(", ");
                }
            }

            sqlBuilder.append(")");

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sqlBuilder.toString());
            }

            return "表创建成功";

        } catch (Exception e) {
            throw new RuntimeException("建表失败: " + e.getMessage(), e);
        }
    }

    private String inferColumnType(List<String[]> sampleData, int columnIndex) {
        int intCount = 0;
        int bigIntCount = 0;
        int decimalCount = 0;
        int boolCount = 0;
        int dateCount = 0;
        int totalNonNull = 0;

        for (String[] row : sampleData) {
            if (columnIndex >= row.length) continue;

            String value = row[columnIndex];
            if (value == null || value.isEmpty()) continue;

            totalNonNull++;

            // Check integer
            try {
                long l = Long.parseLong(value.trim());
                if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
                    bigIntCount++;
                } else {
                    intCount++;
                }
                continue;
            } catch (NumberFormatException ignored) {}

            // Check decimal
            try {
                new BigDecimal(value.trim());
                decimalCount++;
                continue;
            } catch (NumberFormatException ignored) {}

            // Check boolean
            String lower = value.trim().toLowerCase();
            if ("true".equals(lower) || "false".equals(lower)) {
                boolCount++;
                continue;
            }

            // Check date
            if (isDateLike(value)) {
                dateCount++;
                continue;
            }
        }

        // Determine type based on majority
        if (totalNonNull == 0) {
            return "VARCHAR(255)";
        }

        int maxCount = Math.max(Math.max(Math.max(intCount, bigIntCount),
                Math.max(decimalCount, boolCount)), dateCount);

        if (maxCount == intCount) {
            return "INT";
        } else if (maxCount == bigIntCount) {
            return "BIGINT";
        } else if (maxCount == decimalCount) {
            return "DECIMAL(32,18)";
        } else if (maxCount == boolCount) {
            return "TINYINT(1)";
        } else if (maxCount == dateCount) {
            return "DATETIME";
        } else {
            // Check string length
            int maxLength = 0;
            for (String[] row : sampleData) {
                if (columnIndex < row.length && row[columnIndex] != null) {
                    maxLength = Math.max(maxLength, row[columnIndex].length());
                }
            }

            if (maxLength > 255) {
                return "TEXT";
            } else {
                return "VARCHAR(255)";
            }
        }
    }

    private boolean isDateLike(String value) {
        String[] datePatterns = {
            "\\d{4}-\\d{2}-\\d{2}",
            "\\d{4}/\\d{2}/\\d{2}",
            "\\d{2}-\\d{2}-\\d{4}",
            "\\d{2}/\\d{2}/\\d{4}"
        };

        for (String pattern : datePatterns) {
            if (value.matches(pattern)) {
                return true;
            }
        }
        return false;
    }
}
