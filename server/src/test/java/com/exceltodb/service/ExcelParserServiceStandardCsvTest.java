package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExcelParserServiceStandardCsvTest {

    @Test
    void createsStandardCsvFileForUploadedCsv() throws Exception {
        Path dir = Files.createTempDirectory("uploads");
        AppConfig cfg = new AppConfig();
        cfg.setUploadTempPath(dir.toString());
        ExcelParserService svc = new ExcelParserService(cfg);

        String csv = "\uFEFFcol1,col2\n" +
                "x,\"he\"\"llo\"\n";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "a.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        var parse = svc.parseAndSave(file);
        Path standard = svc.ensureStandardCsv(parse.getFilename(), 0);

        assertTrue(Files.exists(standard));
        String content = Files.readString(standard, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\"col1\",\"col2\"\n"));
        assertTrue(content.contains("\"x\",\"he\"\"llo\"\n"));
    }

    @Test
    void createsStandardCsvFileForUploadedExcel() throws Exception {
        Path dir = Files.createTempDirectory("uploads");
        AppConfig cfg = new AppConfig();
        cfg.setUploadTempPath(dir.toString());
        ExcelParserService svc = new ExcelParserService(cfg);

        byte[] xlsxBytes;
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("S1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("a");
            header.createCell(1).setCellValue("b");

            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("x");
            r1.createCell(1).setCellValue("he\"llo");

            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("y");
            // r2 cell(1) intentionally missing -> should become empty string

            wb.write(out);
            xlsxBytes = out.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "t.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes
        );
        var parse = svc.parseAndSave(file);

        Path standard = svc.ensureStandardCsv(parse.getFilename(), 0);
        assertTrue(Files.exists(standard));

        String content = Files.readString(standard, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\"a\",\"b\"\n"));
        assertTrue(content.contains("\"x\",\"he\"\"llo\"\n"));
        assertTrue(content.contains("\"y\",\"\"\n"));
    }
}

