package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.DatabaseInfo;
import com.exceltodb.model.TablePreviewResponse;
import com.exceltodb.model.TableInfo;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DbService {

    private final AppConfig appConfig;
    private final DataSourceConfig dataSourceConfig;

    public DbService(AppConfig appConfig, DataSourceConfig dataSourceConfig) {
        this.appConfig = appConfig;
        this.dataSourceConfig = dataSourceConfig;
    }

    public List<DatabaseInfo> getAllDatabases() {
        return appConfig.getDatabases();
    }

    public boolean testConnection(String databaseId) {
        try {
            return dataSourceConfig.testConnection(databaseId);
        } catch (Exception e) {
            throw new RuntimeException("数据库连接测试失败: " + e.getMessage(), e);
        }
    }

    public List<TableInfo> getAllTables(String databaseId) {
        List<TableInfo> tables = new ArrayList<>();

        try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();

            String[] types = {"TABLE"};
            try (ResultSet rs = metaData.getTables(catalog, null, "%", types)) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    TableInfo tableInfo = new TableInfo();
                    tableInfo.setName(tableName);

                    // Get columns and primary key
                    List<String> columns = new ArrayList<>();
                    List<String> excludedColumns = new ArrayList<>();
                    String primaryKey = getPrimaryKey(metaData, catalog, tableName);

                    // Get column details
                    try (ResultSet colRs = metaData.getColumns(catalog, null, tableName, null)) {
                        while (colRs.next()) {
                            String colName = colRs.getString("COLUMN_NAME");
                            columns.add(colName);
                        }
                    }

                    // Query INFORMATION_SCHEMA for columns with default values or ON UPDATE
                    // Only these columns are excluded from matching (NOT primary keys)
                    String schemaName = conn.getCatalog();
                    String excludedColsSql = "SELECT COLUMN_NAME, COLUMN_DEFAULT, LOWER(EXTRA) as EXTRA_LOWER FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                            "AND (COLUMN_DEFAULT IS NOT NULL AND COLUMN_DEFAULT != '' " +
                            "     OR LOWER(EXTRA) LIKE '%on update%')";
                    try (PreparedStatement pstmt = conn.prepareStatement(excludedColsSql)) {
                        pstmt.setString(1, schemaName);
                        pstmt.setString(2, tableName);
                        try (ResultSet exRs = pstmt.executeQuery()) {
                            while (exRs.next()) {
                                String colName = exRs.getString("COLUMN_NAME");
                                String defaultVal = exRs.getString("COLUMN_DEFAULT");
                                String extraLower = exRs.getString("EXTRA_LOWER");
                                boolean hasDefault = defaultVal != null && !defaultVal.trim().isEmpty();
                                boolean hasOnUpdate = extraLower != null && extraLower.contains("on update");
                                if ((hasDefault || hasOnUpdate) && !excludedColumns.contains(colName)) {
                                    excludedColumns.add(colName);
                                }
                            }
                        }
                    }

                    tableInfo.setColumnCount(columns.size());
                    tableInfo.setColumns(columns);
                    tableInfo.setPrimaryKey(primaryKey);
                    tableInfo.setExcludedColumns(excludedColumns);

                    tables.add(tableInfo);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }

        return tables;
    }

    public List<String> getAllTableNames(String databaseId) {
        List<String> names = new ArrayList<>();
        try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();
            String[] types = {"TABLE"};
            try (ResultSet rs = metaData.getTables(catalog, null, "%", types)) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取表名列表失败: " + e.getMessage(), e);
        }
        return names;
    }

    public TablePreviewResponse getTablePreview(String databaseId, String tableName, int limit) {
        int cappedLimit = Math.min(5, Math.max(1, limit));
        TablePreviewResponse response = new TablePreviewResponse();
        response.setTableName(tableName);

        try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection();
             Statement stmt = conn.createStatement()) {
            List<String> columns = new ArrayList<>();
            String columnsSql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                    "ORDER BY ORDINAL_POSITION";
            try (PreparedStatement colStmt = conn.prepareStatement(columnsSql)) {
                colStmt.setString(1, conn.getCatalog());
                colStmt.setString(2, tableName);
                try (ResultSet colRs = colStmt.executeQuery()) {
                    while (colRs.next()) {
                        columns.add(colRs.getString("COLUMN_NAME"));
                    }
                }
            }
            response.setColumns(columns);

            String escapedTableName = tableName.replace("`", "``");
            String sql = "SELECT * FROM `" + escapedTableName + "` LIMIT " + cappedLimit;
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData resultSetMetaData = rs.getMetaData();
                int columnCount = resultSetMetaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(resultSetMetaData.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            response.setRows(rows);
            return response;
        } catch (SQLException e) {
            throw new RuntimeException("预览目标表失败: " + e.getMessage(), e);
        }
    }

    private String getPrimaryKey(DatabaseMetaData metaData, String catalog, String tableName) throws SQLException {
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, null, tableName)) {
            if (rs.next()) {
                return rs.getString("COLUMN_NAME");
            }
        }
        return null;
    }

    public TableInfo getTableInfo(String databaseId, String tableName) {
        TableInfo tableInfo = new TableInfo();
        List<String> columns = new ArrayList<>();

        try (Connection conn = dataSourceConfig.getDataSource(databaseId).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();

            String primaryKey = getPrimaryKey(metaData, catalog, tableName);

            try (ResultSet colRs = metaData.getColumns(catalog, null, tableName, null)) {
                while (colRs.next()) {
                    columns.add(colRs.getString("COLUMN_NAME"));
                }
            }

            List<String> excludedColumns = new ArrayList<>();
            String schemaName = conn.getCatalog();
            String excludedColsSql = "SELECT COLUMN_NAME, COLUMN_DEFAULT, LOWER(EXTRA) as EXTRA_LOWER FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                    "AND (COLUMN_DEFAULT IS NOT NULL AND COLUMN_DEFAULT != '' " +
                    "     OR LOWER(EXTRA) LIKE '%on update%')";
            try (PreparedStatement pstmt = conn.prepareStatement(excludedColsSql)) {
                pstmt.setString(1, schemaName);
                pstmt.setString(2, tableName);
                try (ResultSet exRs = pstmt.executeQuery()) {
                    while (exRs.next()) {
                        String colName = exRs.getString("COLUMN_NAME");
                        String defaultVal = exRs.getString("COLUMN_DEFAULT");
                        String extraLower = exRs.getString("EXTRA_LOWER");
                        boolean hasDefault = defaultVal != null && !defaultVal.trim().isEmpty();
                        boolean hasOnUpdate = extraLower != null && extraLower.contains("on update");
                        if ((hasDefault || hasOnUpdate) && !excludedColumns.contains(colName)) {
                            excludedColumns.add(colName);
                        }
                    }
                }
            }

            tableInfo.setName(tableName);
            tableInfo.setColumnCount(columns.size());
            tableInfo.setColumns(columns);
            tableInfo.setPrimaryKey(primaryKey);
            tableInfo.setExcludedColumns(excludedColumns);

        } catch (SQLException e) {
            throw new RuntimeException("获取表信息失败: " + e.getMessage(), e);
        }

        return tableInfo;
    }
}
