package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

public class ImportServiceRoutingTest {

    @Test
    void bulkLoadEnabled_routesToBulkLoadService() throws Exception {
        DataSourceConfig dataSourceConfig = mock(DataSourceConfig.class);
        ExcelParserService excelParserService = mock(ExcelParserService.class);
        DbService dbService = mock(DbService.class);
        AppConfig appConfig = mock(AppConfig.class);
        ImportHeartbeatStore heartbeatStore = mock(ImportHeartbeatStore.class);
        BulkLoadImportService bulkLoadImportService = mock(BulkLoadImportService.class);

        DataSource ds = mock(DataSource.class);
        when(dataSourceConfig.getDataSource("db1")).thenReturn(ds);
        when(appConfig.isBulkLoadEnabled()).thenReturn(true);
        when(excelParserService.getParseResult("a.csv")).thenReturn(null);

        ImportRequest request = new ImportRequest();
        request.setDatabaseId("db1");
        request.setFilename("a.csv");
        request.setTableName("t");
        request.setRequestId("req-1");

        ImportResult expected = new ImportResult();
        expected.setSuccess(true);
        expected.setMessage("ok");

        when(bulkLoadImportService.importWithLoadData(same(ds), same(request))).thenReturn(expected);

        ImportService importService = new ImportService(
                dataSourceConfig,
                excelParserService,
                dbService,
                appConfig,
                heartbeatStore,
                bulkLoadImportService
        );

        ImportResult actual = importService.importData(request);

        assertTrue(actual.isSuccess());
        assertEquals("ok", actual.getMessage());
        verify(bulkLoadImportService, times(1)).importWithLoadData(same(ds), same(request));
    }

    @Test
    void bulkLoadDisabled_fallsBackToJdbcPath() throws Exception {
        DataSourceConfig dataSourceConfig = mock(DataSourceConfig.class);
        ExcelParserService excelParserService = mock(ExcelParserService.class);
        DbService dbService = mock(DbService.class);
        AppConfig appConfig = mock(AppConfig.class);
        ImportHeartbeatStore heartbeatStore = mock(ImportHeartbeatStore.class);
        BulkLoadImportService bulkLoadImportService = mock(BulkLoadImportService.class);

        DataSource ds = mock(DataSource.class);
        when(dataSourceConfig.getDataSource("db1")).thenReturn(ds);
        when(appConfig.isBulkLoadEnabled()).thenReturn(false);

        when(ds.getConnection()).thenThrow(new SQLException("boom"));

        ImportRequest request = new ImportRequest();
        request.setDatabaseId("db1");
        request.setFilename("a.csv");
        request.setTableName("t");
        request.setRequestId("req-2");

        ImportService importService = new ImportService(
                dataSourceConfig,
                excelParserService,
                dbService,
                appConfig,
                heartbeatStore,
                bulkLoadImportService
        );

        ImportResult actual = importService.importData(request);

        assertFalse(actual.isSuccess());
        verify(bulkLoadImportService, never()).importWithLoadData(any(), any());
        verify(ds, times(1)).getConnection();
    }
}

