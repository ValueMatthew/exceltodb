package com.exceltodb.controller;

import com.exceltodb.model.TablePreviewResponse;
import com.exceltodb.service.DbService;
import com.exceltodb.service.ExcelParserService;
import com.exceltodb.service.ImportHeartbeatStore;
import com.exceltodb.service.ImportService;
import com.exceltodb.service.TableMatcherService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExcelController.class)
class ExcelControllerTablePreviewTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    DbService dbService;
    @MockBean
    ExcelParserService excelParserService;
    @MockBean
    TableMatcherService tableMatcherService;
    @MockBean
    ImportService importService;
    @MockBean
    ImportHeartbeatStore heartbeatStore;

    @Test
    void tablePreview_returnsColumnsAndRows() throws Exception {
        TablePreviewResponse preview = new TablePreviewResponse();
        preview.setTableName("orders");
        preview.setColumns(List.of("id", "name"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "a");
        preview.setRows(List.of(row));

        Mockito.when(dbService.getTablePreview("prod_erp", "orders", 5)).thenReturn(preview);

        mvc.perform(get("/api/table-preview")
                        .param("databaseId", "prod_erp")
                        .param("tableName", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("orders"))
                .andExpect(jsonPath("$.columns[0]").value("id"))
                .andExpect(jsonPath("$.columns[1]").value("name"))
                .andExpect(jsonPath("$.rows[0].id").value(1))
                .andExpect(jsonPath("$.rows[0].name").value("a"));
    }

    @Test
    void tablePreview_dbFailure_returns500() throws Exception {
        Mockito.when(dbService.getTablePreview("prod_erp", "orders", 5))
                .thenThrow(new RuntimeException("db error"));

        mvc.perform(get("/api/table-preview")
                        .param("databaseId", "prod_erp")
                        .param("tableName", "orders"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void tablePreview_blankDatabaseId_returns500AndDoesNotCallDb() throws Exception {
        mvc.perform(get("/api/table-preview")
                        .param("databaseId", " ")
                        .param("tableName", "orders"))
                .andExpect(status().isInternalServerError());

        verify(dbService, never()).getTablePreview(anyString(), anyString(), anyInt());
    }

    @Test
    void tablePreview_blankTableName_returns500AndDoesNotCallDb() throws Exception {
        mvc.perform(get("/api/table-preview")
                        .param("databaseId", "prod_erp")
                        .param("tableName", " "))
                .andExpect(status().isInternalServerError());

        verify(dbService, never()).getTablePreview(anyString(), anyString(), anyInt());
    }
}
