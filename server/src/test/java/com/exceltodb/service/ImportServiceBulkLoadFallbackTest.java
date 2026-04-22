package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.config.DataSourceConfig;
import com.exceltodb.model.ImportRequest;
import com.exceltodb.model.ImportResult;
import com.exceltodb.model.ParseResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ImportServiceBulkLoadFallbackTest {

    @Test
    void bulkLoad_success_but_zero_rows_with_nonempty_file_falls_back_to_jdbc() throws Exception {
        DataSourceConfig dataSourceConfig = mock(DataSourceConfig.class);
        ExcelParserService excelParserService = mock(ExcelParserService.class);
        DbService dbService = mock(DbService.class);
        AppConfig appConfig = mock(AppConfig.class);
        ImportHeartbeatStore heartbeatStore = mock(ImportHeartbeatStore.class);
        BulkLoadImportService bulk = mock(BulkLoadImportService.class);

        DataSource ds = mock(DataSource.class);
        when(dataSourceConfig.getDataSource("db1")).thenReturn(ds);
        when(appConfig.isBulkLoadEnabled()).thenReturn(true);

        ParseResult pr = new ParseResult();
        pr.setRowCount(70000);
        when(excelParserService.getParseResult("f.csv")).thenReturn(pr);

        ImportResult bulkRes = new ImportResult();
        bulkRes.setSuccess(true);
        bulkRes.setImportedRows(0);
        bulkRes.setMessage("导入成功");
        when(bulk.importWithLoadData(same(ds), any(ImportRequest.class))).thenReturn(bulkRes);

        when(ds.getConnection()).thenThrow(new RuntimeException("jdbc-called"));

        ImportRequest req = new ImportRequest();
        req.setDatabaseId("db1");
        req.setFilename("f.csv");
        req.setTableName("t");
        req.setImportMode("INCREMENTAL");
        req.setConflictStrategy("ERROR");
        req.setRequestId("rid");

        ImportService svc = new ImportService(dataSourceConfig, excelParserService, dbService, appConfig, heartbeatStore, bulk);
        svc.importData(req);

        verify(bulk, times(1)).importWithLoadData(same(ds), same(req));
        verify(ds, atLeastOnce()).getConnection();
    }
}
