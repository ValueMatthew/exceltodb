package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.model.ParseResult;
import com.exceltodb.model.PreviewResult;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

@Service
public class ExcelParserService {

    private final AppConfig appConfig;
    private final Map<String, Path> uploadedFiles = new ConcurrentHashMap<>();
    private final Map<String, ParseResult> parseCache = new ConcurrentHashMap<>();
    private final Map<String, PreviewResult> previewCache = new ConcurrentHashMap<>();
    private static final int DEFAULT_PREVIEW_ROWS = 100;

    public ExcelParserService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public ParseResult parseAndSave(MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                throw new RuntimeException("文件名为空");
            }

            // Save file to temp directory (always use absolute + normalized path)
            Path uploadDir = Paths.get(appConfig.getUploadTempPath()).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);

            // Prevent odd path behavior if filename contains separators
            String safeOriginalName = Paths.get(originalFilename).getFileName().toString();
            String uniqueFilename = System.currentTimeMillis() + "_" + safeOriginalName;
            Path filePath = uploadDir.resolve(uniqueFilename).toAbsolutePath().normalize();
            Files.createDirectories(filePath.getParent());
            file.transferTo(filePath);
            uploadedFiles.put(uniqueFilename, filePath);
            // Drop any stale cache for the same generated name (defensive)
            parseCache.remove(uniqueFilename);
            previewCache.remove(uniqueFilename);

            // Parse based on extension
            String lowerName = originalFilename.toLowerCase();
            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                ParseResult result = parseExcel(filePath, uniqueFilename);
                parseCache.put(uniqueFilename, result);
                return result;
            } else if (lowerName.endsWith(".csv")) {
                ParseResult result = parseCsv(filePath, uniqueFilename);
                parseCache.put(uniqueFilename, result);
                return result;
            } else {
                throw new RuntimeException("不支持的文件格式: " + originalFilename);
            }
        } catch (IOException | InvalidFormatException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    private ParseResult parseExcel(Path filePath, String filename) throws IOException, InvalidFormatException {
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
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
        try (CSVReader reader = createCsvReader(filePath)) {
            String[] header = reader.readNext();
            if (header == null) {
                throw new RuntimeException("CSV文件为空");
            }
            stripBomInPlace(header);

            int rowCount = 0;
            while (reader.readNext() != null) {
                rowCount++;
            }

            ParseResult result = new ParseResult();
            result.setFilename(filename);
            result.setRowCount(rowCount); // exclude header
            result.setColumns(Arrays.asList(header));
            result.setSheetName("Sheet1");

            return result;
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    public PreviewResult getPreview(String filename, int maxRows) throws IOException, InvalidFormatException {
        // Cache only for the standard preview size to maximize hit rate
        if (maxRows == DEFAULT_PREVIEW_ROWS) {
            PreviewResult cached = previewCache.get(filename);
            if (cached != null) return cached;
        }

        Path filePath = uploadedFiles.get(filename);
        if (filePath == null) {
            throw new RuntimeException("文件不存在或已过期: " + filename);
        }

        String lowerName = filename.toLowerCase();
        PreviewResult result;
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            result = getExcelPreview(filePath, filename, maxRows);
        } else if (lowerName.endsWith(".csv")) {
            result = getCsvPreview(filePath, filename, maxRows);
        } else {
            throw new RuntimeException("不支持的文件格式");
        }

        if (maxRows == DEFAULT_PREVIEW_ROWS) {
            previewCache.put(filename, result);
        }
        return result;
    }

    private PreviewResult getExcelPreview(Path filePath, String filename, int maxRows) throws IOException, InvalidFormatException {
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
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
        try (CSVReader reader = createCsvReader(filePath)) {
            PreviewResult result = new PreviewResult();
            result.setFilename(filename);
            result.setSheetName("Sheet1");

            String[] header = reader.readNext();
            if (header == null) {
                result.setTotalRows(0);
                return result;
            }
            stripBomInPlace(header);
            result.setColumns(Arrays.asList(header));

            List<Map<String, Object>> previewRows = new ArrayList<>();
            int totalRows = 0;
            String[] line;
            while ((line = reader.readNext()) != null) {
                totalRows++;
                if (previewRows.size() >= maxRows) {
                    continue;
                }
                Map<String, Object> rowData = new LinkedHashMap<>();
                for (int j = 0; j < result.getColumns().size(); j++) {
                    rowData.put(result.getColumns().get(j), j < line.length ? line[j] : "");
                }
                previewRows.add(rowData);
            }
            result.setPreviewRows(previewRows);
            result.setTotalRows(totalRows);

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

    public List<String[]> readAllData(String filename) throws IOException, InvalidFormatException {
        // If we've already previewed, we can reuse the parsed columns for header length, but
        // readAllData is still a full read; caller (ImportService) should prefer streaming where possible.
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

    private List<String[]> readExcelAllData(Path filePath) throws IOException, InvalidFormatException {
        List<String[]> data = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
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
        try (CSVReader reader = createCsvReader(filePath)) {
            List<String[]> all = reader.readAll();
            if (!all.isEmpty()) {
                stripBomInPlace(all.get(0));
            }
            return all;
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    public CSVReader createCsvReader(Path filePath) throws IOException {
        Charset charset = detectTextCharset(filePath);
        return new CSVReader(new BufferedReader(new InputStreamReader(Files.newInputStream(filePath), charset)));
    }

    /**
     * Detect common encodings for CSV/text files.
     * - UTF-8 BOM -> UTF-8
     * - UTF-16 BOM -> UTF-16
     * - Valid UTF-8 -> UTF-8
     * - Otherwise -> GBK (common for Windows-exported CSV with Chinese)
     */
    private Charset detectTextCharset(Path filePath) throws IOException {
        byte[] sample = readSampleBytes(filePath, 8192);
        if (sample.length >= 3
                && (sample[0] & 0xFF) == 0xEF
                && (sample[1] & 0xFF) == 0xBB
                && (sample[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (sample.length >= 2) {
            int b0 = sample[0] & 0xFF;
            int b1 = sample[1] & 0xFF;
            if (b0 == 0xFE && b1 == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
        }
        if (isValidUtf8(sample)) {
            return StandardCharsets.UTF_8;
        }
        return Charset.forName("GBK");
    }

    private byte[] readSampleBytes(Path filePath, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(filePath)) {
            return in.readNBytes(maxBytes);
        }
    }

    private boolean isValidUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer ignored = decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private void stripBomInPlace(String[] row) {
        if (row == null || row.length == 0 || row[0] == null) return;
        row[0] = stripBom(row[0]);
    }

    private String stripBom(String s) {
        if (s == null || s.isEmpty()) return s;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    public Path getFilePath(String filename) {
        return uploadedFiles.get(filename);
    }

    public ParseResult getParseResult(String filename) {
        return parseCache.get(filename);
    }
}
