package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.model.ParseResult;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelParserServiceMultiSheetTest {

    @TempDir
    Path tempDir;

    AppConfig appConfig;
    ExcelParserService excelParserService;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class);
        when(appConfig.getUploadTempPath()).thenReturn(tempDir.toString());
        excelParserService = new ExcelParserService(appConfig);
    }

    @Test
    void parseMultiSheet_readSecondSheet() throws Exception {
        byte[] bytes;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet a = wb.createSheet("A");
            a.createRow(0).createCell(0).setCellValue("h0");
            Sheet b = wb.createSheet("B");
            b.createRow(0).createCell(0).setCellValue("h1");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            bytes = bos.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "twosheets.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        ParseResult pr = excelParserService.parseAndSave(file);
        assertNotNull(pr.getSheets());
        assertEquals(2, pr.getSheets().size());
        assertEquals(0, pr.getSheetIndex());
        assertEquals("h0", pr.getColumns().get(0));

        List<String[]> rows = excelParserService.readAllData(pr.getFilename(), 1);
        assertFalse(rows.isEmpty());
        assertEquals("h1", rows.get(0)[0]);
    }
}
