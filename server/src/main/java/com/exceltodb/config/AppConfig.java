package com.exceltodb.config;

import com.exceltodb.model.DatabaseInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class AppConfig {

    private String configFile;
    private String uploadTempPath = "./uploads";
    private int batchSize = 5000;

    private List<DatabaseInfo> databases;

    @PostConstruct
    public void loadConfig() {
        try {
            Yaml yaml = new Yaml();
            InputStream inputStream = new FileInputStream(configFile);
            Map<String, Object> config = yaml.load(inputStream);

            @SuppressWarnings("unchecked")
            Map<String, Object> databasesConfig = (Map<String, Object>) config.get("databases");
            if (databasesConfig != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> dbList = (List<Map<String, Object>>) databasesConfig.get("databases");
                if (dbList != null) {
                    databases = dbList.stream().map(this::mapToDatabaseInfo).toList();
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> uploadConfig = (Map<String, Object>) config.get("upload");
            if (uploadConfig != null) {
                uploadTempPath = (String) uploadConfig.getOrDefault("tempPath", uploadTempPath);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> importConfig = (Map<String, Object>) config.get("import");
            if (importConfig != null) {
                batchSize = (Integer) importConfig.getOrDefault("batchSize", batchSize);
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException("配置文件不存在: " + configFile, e);
        }
    }

    private DatabaseInfo mapToDatabaseInfo(Map<String, Object> map) {
        DatabaseInfo db = new DatabaseInfo();
        db.setId((String) map.get("id"));
        db.setName((String) map.get("name"));
        db.setHost((String) map.get("host"));
        db.setPort((Integer) map.get("port"));
        db.setUsername((String) map.get("username"));
        db.setPassword((String) map.get("password"));
        db.setDatabase((String) map.get("database"));
        return db;
    }

    public DatabaseInfo getDatabaseById(String id) {
        if (databases == null) return null;
        return databases.stream().filter(db -> db.getId().equals(id)).findFirst().orElse(null);
    }
}
