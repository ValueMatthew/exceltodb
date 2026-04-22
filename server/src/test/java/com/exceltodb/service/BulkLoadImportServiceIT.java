package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.DatabaseInfo;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ParseResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkLoadImportServiceIT {

    @SuppressWarnings("resource")
    private static MySQLContainer<?> mysql;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startMysql() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available for Testcontainers");
        mysql = new MySQLContainer<>("mysql:8.0.36").withCommand("--local-infile=1");
        mysql.start();
    }

    @AfterAll
    static void stopMysql() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private AppConfig appConfig;
    private ExcelParserService excelParserService;
    private BulkLoadImportService bulkLoadImportService;
    private HikariDataSource importDataSource;

    @BeforeEach
    void setUp() throws Exception {
        appConfig = new AppConfig();
        appConfig.setUploadTempPath(tempDir.toAbsolutePath().normalize().toString());

        DatabaseInfo dbInfo = new DatabaseInfo();
        dbInfo.setId("it");
        dbInfo.setName("it");
        dbInfo.setHost(mysql.getHost());
        dbInfo.setPort(mysql.getMappedPort(MySQLContainer.MYSQL_PORT));
        dbInfo.setUsername(mysql.getUsername());
        dbInfo.setPassword(mysql.getPassword());
        dbInfo.setDatabase(mysql.getDatabaseName());
        appConfig.setDatabases(List.of(dbInfo));

        DataSourceConfig dataSourceConfig = new DataSourceConfig(appConfig);
        DbService dbService = new DbService(appConfig, dataSourceConfig);
        ImportHeartbeatStore heartbeatStore = new ImportHeartbeatStore();
        excelParserService = new ExcelParserService(appConfig);
        bulkLoadImportService = new BulkLoadImportService(appConfig, excelParserService, heartbeatStore, dbService);

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(mysql.getJdbcUrl()
                + "&allowLoadLocalInfile=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC");
        hc.setUsername(mysql.getUsername());
        hc.setPassword(mysql.getPassword());
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setMaximumPoolSize(4);
        importDataSource = new HikariDataSource(hc);

        try (Connection c = mysql.createConnection("")) {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS target");
                st.execute("CREATE TABLE target (id INT PRIMARY KEY, v VARCHAR(50))");
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (importDataSource != null) {
            importDataSource.close();
        }
    }

    @Test
    void incremental_ignore_insertsRows() throws Exception {
        ParseResult parseResult = uploadCsv("id,v\n1,a\n2,b\n");

        ImportRequest request = baseRequest(parseResult.getFilename());
        request.setImportMode("INCREMENTAL");
        request.setConflictStrategy("IGNORE");

        ImportResult result = bulkLoadImportService.importWithLoadData(importDataSource, request);

        assertTrue(result.isSuccess(), result::getMessage);
        assertEquals(2, result.getImportedRows());
        assertEquals(2, countTargetRows());
    }

    @Test
    void update_updatesExistingRow() throws Exception {
        try (Connection c = mysql.createConnection("");
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO target (id, v) VALUES (2, 'old')");
        }

        ParseResult parseResult = uploadCsv("id,v\n2,new\n");

        ImportRequest request = baseRequest(parseResult.getFilename());
        request.setImportMode("INCREMENTAL");
        request.setConflictStrategy("UPDATE");

        ImportResult result = bulkLoadImportService.importWithLoadData(importDataSource, request);

        assertTrue(result.isSuccess(), result::getMessage);
        assertEquals(1, result.getImportedRows());
        assertEquals(1, countTargetRows());
        assertEquals("new", queryVForId(2));
    }

    @Test
    void truncate_leavesOnlyImportedRow() throws Exception {
        try (Connection c = mysql.createConnection("");
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO target (id, v) VALUES (1, 'x')");
            st.executeUpdate("INSERT INTO target (id, v) VALUES (2, 'y')");
            st.executeUpdate("INSERT INTO target (id, v) VALUES (3, 'z')");
        }

        ParseResult parseResult = uploadCsv("id,v\n9,single\n");

        ImportRequest request = baseRequest(parseResult.getFilename());
        request.setImportMode("TRUNCATE");
        request.setConflictStrategy("IGNORE");

        ImportResult result = bulkLoadImportService.importWithLoadData(importDataSource, request);

        assertTrue(result.isSuccess(), result::getMessage);
        assertEquals(1, result.getImportedRows());
        assertEquals(1, countTargetRows());
        assertEquals("single", queryVForId(9));
    }

    private ParseResult uploadCsv(String csvBody) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "t.csv",
                "text/csv",
                csvBody.getBytes(StandardCharsets.UTF_8));
        return excelParserService.parseAndSave(file);
    }

    private ImportRequest baseRequest(String filename) {
        ImportRequest request = new ImportRequest();
        request.setDatabaseId("it");
        request.setFilename(filename);
        request.setTableName("target");
        request.setRequestId(UUID.randomUUID().toString());
        return request;
    }

    private int countTargetRows() throws Exception {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM target")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String queryVForId(int id) throws Exception {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = c.prepareStatement("SELECT v FROM target WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
