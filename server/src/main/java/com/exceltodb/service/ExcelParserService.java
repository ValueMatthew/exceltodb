package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.model.ParseResult;
import com.exceltodb.model.PreviewResult;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class ExcelParserService {

    private final AppConfig appConfig;
    private final Map<String, Path> uploadedFiles = new HashMap<>();

    public ExcelParserService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public ParseResult parseAndSave(MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                throw new RuntimeException("文件名为空");
            }

            // Save file to temp directory
            Path uploadDir = Paths.get(appConfig.getUploadTempPath());
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String uniqueFilename = System.currentTimeMillis() + "_" + originalFilename;
            Path filePath = uploadDir.resolve(uniqueFilename);
            file.transferTo(filePath.toFile());
            uploadedFiles.put(uniqueFilename, filePath);

            // Parse based on extension
            String lowerName = originalFilename.toLowerCase();
            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                return parseExcel(filePath, uniqueFilename);
            } else if (lowerName.endsWith(".csv")) {
                return parseCsv(filePath, uniqueFilename);
            } else {
                throw new RuntimeException("不支持的文件格式: " + originalFilename);
            }
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    private ParseResult parseExcel(Path filePath, String filename) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(filePath.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            if (headerRow == null) {
                throw new RuntimeException("Excel文件为空或没有表头");
            }

            ParseResult result = new ParseResult();
            result.setFilename(filename);

            int lastRowNum = sheet.getLastRowNum();
            result.setRowCount(lastRowNum);

            short lastCellNum = headerRow.getLastCellNum();
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < lastCellNum; i++) {
                Cell cell = headerRow.getCell(i);
                columns.add(getCellValueAsString(cell));
            }
            result.setColumns(columns);
            result.setSheetName(sheet.getSheetName());

            return result;
        } catch (IOException e) {
            throw new RuntimeException("Excel解析失败: " + e.getMessage(), e);
        }
    }

    private ParseResult parseCsv(Path filePath, String filename) throws IOException {
        try (CSVReader reader = new CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> allLines = reader.readAll();

            if (allLines.isEmpty()) {
                throw new RuntimeException("CSV文件为空");
            }

            ParseResult result = new ParseResult();
            result.setFilename(filename);
            result.setRowCount(allLines.size() - 1); // exclude header
            result.setColumns(Arrays.asList(allLines.get(0)));
            result.setSheetName("Sheet1");

            return result;
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    public PreviewResult getPreview(String filename, int maxRows) {
        Path filePath = uploadedFiles.get(filename);
        if (filePath == null) {
            throw new RuntimeException("文件不存在或已过期: " + filename);
        }

        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return getExcelPreview(filePath, filename, maxRows);
        } else if (lowerName.endsWith(".csv")) {
            return getCsvPreview(filePath, filename, maxRows);
        } else {
            throw new RuntimeException("不支持的文件格式");
        }
    }

    private PreviewResult getExcelPreview(Path filePath, String filename, int maxRows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(filePath.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            PreviewResult result = new PreviewResult();
            result.setFilename(filename);
            result.setTotalRows(sheet.getLastRowNum());
            result.setSheetName(sheet.getSheetName());

            // Parse columns
            short lastCellNum = headerRow.getLastCellNum();
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < lastCellNum; i++) {
                Cell cell = headerRow.getCell(i);
                columns.add(getCellValueAsString(cell));
            }
            result.setColumns(columns);

            // Parse data rows
            List<Map<String, Object>> previewRows = new ArrayList<>();
            int rowCount = Math.min(maxRows, sheet.getLastRowNum());

            for (int i = 1; i <= rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, Object> rowData = new LinkedHashMap<>();
                for (int j = 0; j < columns.size(); j++) {
                    Cell cell = row.getCell(j);
                    rowData.put(columns.get(j), getCellValueAsString(cell));
                }
                previewRows.add(rowData);
            }
            result.setPreviewRows(previewRows);

            return result;
        }
    }

    private PreviewResult getCsvPreview(Path filePath, String filename, int maxRows) throws IOException {
        try (CSVReader reader = new CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> allLines = reader.readAll();

            PreviewResult result = new PreviewResult();
            result.setFilename(filename);
            result.setTotalRows(allLines.size() - 1);
            result.setSheetName("Sheet1");

            if (allLines.isEmpty()) {
                return result;
            }

            result.setColumns(Arrays.asList(allLines.get(0)));

            List<Map<String, Object>> previewRows = new ArrayList<>();
            int rowCount = Math.min(maxRows, allLines.size() - 1);

            for (int i = 1; i <= rowCount; i++) {
                String[] line = allLines.get(i);
                Map<String, Object> rowData = new LinkedHashMap<>();
                for (int j = 0; j < result.getColumns().size(); j++) {
                    rowData.put(result.getColumns().get(j), j < line.length ? line[j] : "");
                }
                previewRows.add(rowData);
            }
            result.setPreviewRows(previewRows);

            return result;
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                } else {
                    double val = cell.getNumericCellValue();
                    if (val == Math.floor(val)) {
                        yield String.valueOf((long) val);
                    } else {
                        yield String.valueOf(val);
                    }
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }

    public List<String[]> readAllData(String filename) throws IOException {
        Path filePath = uploadedFiles.get(filename);
        if (filePath == null) {
            throw new RuntimeException("文件不存在: " + filename);
        }

        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return readExcelAllData(filePath);
        } else if (lowerName.endsWith(".csv")) {
            return readCsvAllData(filePath);
        } else {
            throw new RuntimeException("不支持的文件格式");
        }
    }

    private List<String[]> readExcelAllData(Path filePath) throws IOException {
        List<String[]> data = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(filePath.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return data;

            int lastCellNum = headerRow.getLastCellNum();

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String[] rowData = new String[lastCellNum];
                for (int j = 0; j < lastCellNum; j++) {
                    Cell cell = row.getCell(j);
                    rowData[j] = getCellValueAsString(cell);
                }
                data.add(rowData);
            }
        }

        return data;
    }

    private List<String[]> readCsvAllData(Path filePath) throws IOException {
        try (CSVReader reader = new CSVReader(new FileReader(filePath.toFile()))) {
            return reader.readAll();
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    public Path getFilePath(String filename) {
        return uploadedFiles.get(filename);
    }
}
