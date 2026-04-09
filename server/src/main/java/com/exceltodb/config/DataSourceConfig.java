package com.exceltodb.config;

import com.exceltodb.model.DatabaseInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DataSourceConfig {

    private final AppConfig appConfig;
    private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    public DataSourceConfig(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public DataSource getDataSource(String databaseId) {
        return dataSourceCache.computeIfAbsent(databaseId, this::createDataSource);
    }

    private DataSource createDataSource(String databaseId) {
        DatabaseInfo dbInfo = appConfig.getDatabaseById(databaseId);
        if (dbInfo == null) {
            throw new RuntimeException("数据库配置不存在: " + databaseId);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                dbInfo.getHost(), dbInfo.getPort(), dbInfo.getDatabase()));
        config.setUsername(dbInfo.getUsername());
        config.setPassword(dbInfo.getPassword());
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }

    public boolean testConnection(String databaseId) throws SQLException {
        DataSource ds = getDataSource(databaseId);
        try (Connection conn = ds.getConnection()) {
            return conn.isValid(5);
        }
    }

    public void clearCache() {
        dataSourceCache.clear();
    }
}
