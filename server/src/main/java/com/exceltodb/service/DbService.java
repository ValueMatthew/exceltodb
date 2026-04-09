package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.DatabaseInfo;
import com.exceltodb.model.TableInfo;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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

            String[] types = {"TABLE"};
            try (ResultSet rs = metaData.getTables(null, null, "%", types)) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    TableInfo tableInfo = new TableInfo();
                    tableInfo.setName(tableName);

                    // Get columns
                    List<String> columns = new ArrayList<>();
                    String primaryKey = getPrimaryKey(metaData, tableName);

                    try (ResultSet colRs = metaData.getColumns(null, null, tableName, null)) {
                        while (colRs.next()) {
                            columns.add(colRs.getString("COLUMN_NAME"));
                        }
                    }

                    tableInfo.setColumnCount(columns.size());
                    tableInfo.setColumns(columns);
                    tableInfo.setPrimaryKey(primaryKey);

                    tables.add(tableInfo);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }

        return tables;
    }

    private String getPrimaryKey(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
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

            String primaryKey = getPrimaryKey(metaData, tableName);

            try (ResultSet colRs = metaData.getColumns(null, null, tableName, null)) {
                while (colRs.next()) {
                    columns.add(colRs.getString("COLUMN_NAME"));
                }
            }

            tableInfo.setName(tableName);
            tableInfo.setColumnCount(columns.size());
            tableInfo.setColumns(columns);
            tableInfo.setPrimaryKey(primaryKey);

        } catch (SQLException e) {
            throw new RuntimeException("获取表信息失败: " + e.getMessage(), e);
        }

        return tableInfo;
    }
}
