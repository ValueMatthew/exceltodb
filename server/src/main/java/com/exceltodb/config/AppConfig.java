package com.exceltodb.config;

import com.exceltodb.model.DatabaseInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class AppConfig {

    private String configFile;
    private String uploadTempPath = "./uploads";
    private int batchSize = 5000;
    private boolean bulkLoadEnabled = true;

    private List<DatabaseInfo> databases;

    @PostConstruct
    public void loadConfig() {
        try {
            Yaml yaml = new Yaml();
            InputStream inputStream;
            if (configFile != null && configFile.startsWith("/")) {
                // Absolute path
                inputStream = new FileInputStream(configFile);
            } else {
                // Classpath resource
                inputStream = getClass().getClassLoader().getResourceAsStream(configFile);
                if (inputStream == null) {
                    // Fallback to current directory
                    inputStream = new FileInputStream(configFile);
                }
            }
            Map<String, Object> config = yaml.load(inputStream);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dbList = (List<Map<String, Object>>) config.get("databases");
            if (dbList != null) {
                databases = dbList.stream().map(this::mapToDatabaseInfo).toList();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> uploadConfig = (Map<String, Object>) config.get("upload");
            if (uploadConfig != null) {
                String tempPath = (String) uploadConfig.getOrDefault("tempPath", uploadTempPath);
                uploadTempPath = tempPath;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> importConfig = (Map<String, Object>) config.get("import");
            if (importConfig != null) {
                Object batchSizeValue = importConfig.getOrDefault("batchSize", batchSize);
                batchSize = parseBatchSize(batchSizeValue, batchSize);
                Object bulkLoadEnabledValue = importConfig.get("bulkLoadEnabled");
                if (bulkLoadEnabledValue != null) {
                    bulkLoadEnabled = parseBoolean(bulkLoadEnabledValue, bulkLoadEnabled);
                }
            }

            // Always resolve upload dir to an absolute, normalized path to avoid Tomcat-relative issues.
            Path uploadPath = Path.of(uploadTempPath);
            if (!uploadPath.isAbsolute()) {
                uploadPath = uploadPath.toAbsolutePath();
            }
            uploadTempPath = uploadPath.normalize().toString();
        } catch (FileNotFoundException e) {
            throw new RuntimeException("配置文件不存在: " + configFile, e);
        }
    }

    private static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) {
            int i = n.intValue();
            if (i == 1) return true;
            if (i == 0) return false;
            throw new RuntimeException("配置 import.bulkLoadEnabled 数值仅支持 1/0，实际值: " + value);
        }
        String s = String.valueOf(value).trim();
        if ("1".equals(s)) return true;
        if ("0".equals(s)) return false;
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw new RuntimeException("配置 import.bulkLoadEnabled 仅支持 true/false 或 1/0，实际值: " + value);
    }

    private static int parseBatchSize(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        String s = String.valueOf(value).trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new RuntimeException("配置 import.batchSize 无法解析为整数，实际值: " + value, e);
        }
    }

    private DatabaseInfo mapToDatabaseInfo(Map<String, Object> map) {
        DatabaseInfo db = new DatabaseInfo();
        db.setId(String.valueOf(map.get("id")));
        db.setName(String.valueOf(map.get("name")));
        db.setHost(String.valueOf(map.get("host")));
        Object port = map.get("port");
        db.setPort(port instanceof Integer ? (Integer) port : Integer.parseInt(String.valueOf(port)));
        db.setUsername(String.valueOf(map.get("username")));
        db.setPassword(String.valueOf(map.get("password")));
        db.setDatabase(String.valueOf(map.get("database")));
        return db;
    }

    public DatabaseInfo getDatabaseById(String id) {
        if (databases == null) return null;
        return databases.stream().filter(db -> db.getId().equals(id)).findFirst().orElse(null);
    }
}
