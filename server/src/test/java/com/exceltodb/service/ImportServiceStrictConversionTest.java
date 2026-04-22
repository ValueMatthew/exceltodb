package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.ColumnMeta;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImportServiceStrictConversionTest {

    @Test
    void invalidDatetime_reportsRowAndColumn() throws Exception {
        DataSourceConfig dataSourceConfig = mock(DataSourceConfig.class);
        ExcelParserService excelParserService = mock(ExcelParserService.class);
        DbService dbService = mock(DbService.class);
        AppConfig appConfig = mock(AppConfig.class);
        ImportHeartbeatStore heartbeatStore = mock(ImportHeartbeatStore.class);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        Statement st = mock(Statement.class);

        when(dataSourceConfig.getDataSource("db1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(conn.createStatement()).thenReturn(st);

        // Provide a single-row Excel-like dataset: header + 1 data row with invalid datetime.
        when(excelParserService.readAllData("f.xlsx", 0)).thenReturn(List.of(
                new String[]{"ts"},
                new String[]{"not-a-time"}
        ));

        ColumnMeta meta = new ColumnMeta();
        meta.setName("ts");
        meta.setDataType("datetime");
        meta.setColumnType("datetime");
        meta.setNullable(false);
        when(dbService.getColumnMetas("db1", "t")).thenReturn(List.of(meta));

        ImportService importService = new ImportService(
                dataSourceConfig,
                excelParserService,
                dbService,
                appConfig,
                heartbeatStore
        );

        ImportRequest req = new ImportRequest();
        req.setDatabaseId("db1");
        req.setFilename("f.xlsx");
        req.setSheetIndex(0);
        req.setTableName("t");
        req.setImportMode("INCREMENTAL");
        req.setConflictStrategy("ERROR");
        req.setRequestId("req-1");

        ImportResult result = importService.importData(req);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("第 1 行，第 1 列（ts）解析失败"));
    }
}

