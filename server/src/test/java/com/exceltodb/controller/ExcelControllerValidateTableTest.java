package com.exceltodb.controller;

import com.exceltodb.model.TableInfo;
import com.exceltodb.model.TableRecommendation;
import com.exceltodb.service.DbService;
import com.exceltodb.service.ExcelParserService;
import com.exceltodb.service.ImportService;
import com.exceltodb.service.TableMatcherService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExcelController.class)
class ExcelControllerValidateTableTest {

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

    @Test
    void validateTable_notFound_returnsExistsFalse() throws Exception {
        Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("users"));

        String body = """
                {
                  "databaseId": "prod_erp",
                  "tableName": "orders",
                  "filename": "orders.xlsx",
                  "sheetIndex": 0,
                  "columns": ["id"]
                }
                """;

        mvc.perform(post("/api/validate-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.threshold").value(90))
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.table").value(Matchers.nullValue()));
    }

    @Test
    void validateTable_belowThreshold_returnsReasonBelowThreshold() throws Exception {
        TableInfo info = tableInfo("orders", List.of("id", "order_no"), "id");

        Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("orders"));
        Mockito.when(dbService.getTableInfo("prod_erp", "orders")).thenReturn(info);
        Mockito.when(tableMatcherService.findBestMatch(Mockito.anyList(), Mockito.anyList(), Mockito.eq("orders.xlsx")))
                .thenReturn(recommendation("orders", 50, info.getColumns(), List.of("id"), "id"));

        String body = """
                {
                  "databaseId": "prod_erp",
                  "tableName": "orders",
                  "filename": "orders.xlsx",
                  "sheetIndex": 0,
                  "columns": ["id", "x_not_match"]
                }
                """;

        mvc.perform(post("/api/validate-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.reason").value("BELOW_THRESHOLD"))
                .andExpect(jsonPath("$.threshold").value(90))
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.table.tableName").value("orders"));
    }

    @Test
    void validateTable_pass_returnsNullOrAbsentReasonAndTable() throws Exception {
        TableInfo info = tableInfo("orders", List.of("id", "order_no"), "id");

        Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("orders"));
        Mockito.when(dbService.getTableInfo("prod_erp", "orders")).thenReturn(info);
        Mockito.when(tableMatcherService.findBestMatch(Mockito.anyList(), Mockito.anyList(), Mockito.eq("orders.xlsx")))
                .thenReturn(recommendation("orders", 95, info.getColumns(), List.of("id", "order_no"), "id"));

        String body = """
                {
                  "databaseId": "prod_erp",
                  "tableName": "orders",
                  "filename": "orders.xlsx",
                  "sheetIndex": 0,
                  "columns": ["id", "order_no"]
                }
                """;

        mvc.perform(post("/api/validate-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.threshold").value(90))
                .andExpect(jsonPath("$.score").value(95))
                .andExpect(reasonIsNullOrMissing())
                .andExpect(jsonPath("$.table.tableName").value("orders"));
    }

    private static TableInfo tableInfo(String name, List<String> columns, String primaryKey) {
        TableInfo info = new TableInfo();
        info.setName(name);
        info.setColumns(columns);
        info.setColumnCount(columns.size());
        info.setPrimaryKey(primaryKey);
        info.setExcludedColumns(List.of());
        return info;
    }

    private static TableRecommendation recommendation(String tableName, int score, List<String> columns,
                                                       List<String> matchedColumns, String primaryKey) {
        TableRecommendation recommendation = new TableRecommendation();
        recommendation.setTableName(tableName);
        recommendation.setScore(score);
        recommendation.setColumnCount(columns.size());
        recommendation.setPrimaryKey(primaryKey);
        recommendation.setColumns(columns);
        recommendation.setMatchedColumns(matchedColumns);
        return recommendation;
    }

    private static ResultMatcher reasonIsNullOrMissing() {
        return result -> {
            try {
                jsonPath("$.reason").doesNotExist().match(result);
            } catch (AssertionError missingReasonAssertion) {
                jsonPath("$.reason").value(Matchers.nullValue()).match(result);
            }
        };
    }
}
