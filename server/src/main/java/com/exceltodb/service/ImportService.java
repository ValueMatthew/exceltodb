package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.CreateTableRequest;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.TableInfo;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ImportService {

    private final DataSourceConfig dataSourceConfig;
    private final ExcelParserService excelParserService;
    private final DbService dbService;
    private final AppConfig appConfig;

    public ImportService(DataSourceConfig dataSourceConfig, ExcelParserService excelParserService,
                        DbService dbService, AppConfig appConfig) {
        this.dataSourceConfig = dataSourceConfig;
        this.excelParserService = excelParserService;
        this.dbService = dbService;
        this.appConfig = appConfig;
    }

    public ImportResult importData(ImportRequest request) throws IOException, InvalidFormatException {
        ImportResult result = new ImportResult();

        DataSource ds = dataSourceConfig.getDataSource(request.getDatabaseId());

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Handle truncate mode
                if ("TRUNCATE".equals(request.getImportMode())) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("TRUNCATE TABLE `" + request.getTableName() + "`");
                    }
                }

                // Read all data from file
                List<String[]> allData = excelParserService.readAllData(request.getFilename());
                if (allData.isEmpty()) {
                    throw new RuntimeException("文件没有数据");
                }

                String[] headers = allData.get(0);
                List<String[]> dataRows = allData.subList(1, allData.size());

                // Determine conflict strategy
                String conflictAction = getConflictAction(request, headers);

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
                    int totalRows = dataRows.size();
                    int importedRows = 0;

                    for (int i = 0; i < totalRows; i++) {
                        String[] row = dataRows.get(i);

                        // Skip if row has fewer columns than headers
                        if (row.length < headers.length) {
                            continue;
                        }

                        for (int j = 0; j < headers.length; j++) {
                            pstmt.setObject(j + 1, convertValue(row[j]));
                        }

                        try {
                            int affected = pstmt.executeUpdate();
                            // Count rows that were actually inserted or updated (affected >= 1)
                            // UPSERT: 1=inserted, 2=updated; IGNORE: 0=ignored, 1=inserted
                            if (affected >= 1) {
                                importedRows++;
                            }
                        } catch (SQLException e) {
                            // Primary key conflict or other SQL error
                            // Rollback entire transaction on first error for ERROR strategy
                            if ("ERROR".equals(request.getConflictStrategy())) {
                                throw new RuntimeException("第 " + (i + 1) + " 行导入失败: " + e.getMessage(), e);
                            }
                            // For UPSERT/IGNORE, errors are already handled by the SQL clause
                        }
                    }

                    result.setImportedRows(importedRows);
                    result.setSuccess(true);
                    result.setMessage("导入成功");
                }

                conn.commit();

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("导入失败: " + e.getMessage());
        }

        return result;
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

    private Object convertValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // Try integer
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {}

        // Try double
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {}

        // Try boolean
        String lower = value.trim().toLowerCase();
        if ("true".equals(lower) || "false".equals(lower)) {
            return "true".equals(lower);
        }

        // Try datetime
        try {
            return LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception ignored) {}

        try {
            return LocalDateTime.parse(value.trim().replace(" ", "T"));
        } catch (Exception ignored) {}

        return value;
    }

    public String createTable(CreateTableRequest request) throws IOException, InvalidFormatException {
        DataSource ds = dataSourceConfig.getDataSource(request.getDatabaseId());

        try (Connection conn = ds.getConnection()) {
            // Analyze Excel file to get column info
            List<String[]> allData = excelParserService.readAllData(request.getFilename());
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
