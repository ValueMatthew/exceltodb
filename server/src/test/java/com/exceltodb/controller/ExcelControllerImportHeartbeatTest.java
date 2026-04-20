package com.exceltodb.controller;

import com.exceltodb.model.ImportHeartbeat;
import com.exceltodb.model.ImportStage;
import com.exceltodb.model.ImportStatus;
import com.exceltodb.service.DbService;
import com.exceltodb.service.ImportHeartbeatStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExcelController.class)
class ExcelControllerImportHeartbeatTest {

    @Autowired MockMvc mvc;

    @MockBean DbService dbService;
    @MockBean com.exceltodb.service.ExcelParserService excelParserService;
    @MockBean com.exceltodb.service.TableMatcherService tableMatcherService;
    @MockBean com.exceltodb.service.ImportService importService;
    @MockBean ImportHeartbeatStore heartbeatStore;

    @Test
    void heartbeat_returnsHeartbeatJson() throws Exception {
        ImportHeartbeat hb = new ImportHeartbeat();
        hb.setRequestId("rid-1");
        hb.setStatus(ImportStatus.RUNNING);
        hb.setStage(ImportStage.INSERTING);
        hb.setUpdatedAt(1713600000000L);
        hb.setProcessedRows(1234);
        hb.setMessage("");

        Mockito.when(heartbeatStore.get("rid-1")).thenReturn(hb);

        mvc.perform(get("/api/import/heartbeat").param("requestId", "rid-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("rid-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.stage").value("INSERTING"))
                .andExpect(jsonPath("$.processedRows").value(1234));
    }
}

