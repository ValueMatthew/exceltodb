package com.exceltodb.controller;

import com.exceltodb.service.DbService;
import com.exceltodb.service.ExcelParserService;
import com.exceltodb.service.ImportService;
import com.exceltodb.service.TableMatcherService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExcelController.class)
class ExcelControllerTableNamesTest {

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
    void getTableNames_returnsJsonArray() throws Exception {
        Mockito.when(dbService.getAllTableNames("prod_erp")).thenReturn(List.of("orders", "users"));

        mvc.perform(get("/api/tables/prod_erp/names"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"orders\",\"users\"]"));
    }
}
