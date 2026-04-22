package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import com.exceltodb.model.ParseResult;
import com.exceltodb.model.PreviewResult;
import com.exceltodb.model.SheetSummary;
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
import java.nio.file.StandardCopyOption;
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
            previewCache.keySet().removeIf(k -> k.equals(uniqueFilename) || k.startsWith(uniqueFilename + "#"));

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
            int n = workbook.getNumberOfSheets();
            if (n == 0) {
                throw new RuntimeException("Excel文件不包含任何工作表");
            }

            List<SheetSummary> summaries = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Sheet s = workbook.getSheetAt(i);
                SheetSummary ss = new SheetSummary();
                ss.setIndex(i);
                ss.setName(s.getSheetName());
                // Physical data rows (row 0 = header), not getLastRowNum() which is last index and misleading for sparse sheets.
                ss.setRowCount(countPhysicalDataRows(s));
                summaries.add(ss);
            }

            Sheet sheet = requireSheet(workbook, 0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new RuntimeException("Excel文件为空或没有表头");
            }

            ParseResult result = new ParseResult();
            result.setFilename(filename);
            result.setSheetIndex(0);
            result.setSheets(summaries);

            result.setRowCount(countPhysicalDataRows(sheet));

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

    /** Count physical data rows (row number greater than 0; excludes header row 0). */
    private static int countPhysicalDataRows(Sheet sheet) {
        int n = 0;
        for (Row row : sheet) {
            if (row.getRowNum() > 0) {
                n++;
            }
        }
        return n;
    }

    private Sheet requireSheet(Workbook workbook, int sheetIndex) {
        int n = workbook.getNumberOfSheets();
        if (sheetIndex < 0 || sheetIndex >= n) {
            throw new RuntimeException("工作表索引无效: " + sheetIndex + "（共 " + n + " 个工作表）");
        }
        return workbook.getSheetAt(sheetIndex);
    }

    private static String previewCacheKey(String filename, int sheetIndex) {
        return filename + "#" + sheetIndex;
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
            result.setSheetIndex(0);
            result.setSheets(null);
            result.setRowCount(rowCount); // exclude header
            result.setColumns(Arrays.asList(header));
            result.setSheetName("Sheet1");

            return result;
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }
    }

    public PreviewResult getPreview(String filename, int maxRows, int sheetIndex) throws IOException, InvalidFormatException {
        String cacheKey = previewCacheKey(filename, sheetIndex);
        if (maxRows == DEFAULT_PREVIEW_ROWS) {
            PreviewResult cached = previewCache.get(cacheKey);
            if (cached != null) return cached;
        }

        Path filePath = uploadedFiles.get(filename);
        if (filePath == null) {
            throw new RuntimeException("文件不存在或已过期: " + filename);
        }

        String lowerName = filename.toLowerCase();
        PreviewResult result;
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            result = getExcelPreview(filePath, filename, maxRows, sheetIndex);
        } else if (lowerName.endsWith(".csv")) {
            result = getCsvPreview(filePath, filename, maxRows);
        } else {
            throw new RuntimeException("不支持的文件格式");
        }

        if (maxRows == DEFAULT_PREVIEW_ROWS) {
            previewCache.put(cacheKey, result);
        }
        return result;
    }

    private PreviewResult getExcelPreview(Path filePath, String filename, int maxRows, int sheetIndex) throws IOException, InvalidFormatException {
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            Sheet sheet = requireSheet(workbook, sheetIndex);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new RuntimeException("Excel该工作表为空或没有表头");
            }

            PreviewResult result = new PreviewResult();
            result.setFilename(filename);
            result.setSheetIndex(sheetIndex);
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
            result.setSheetIndex(0);
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

    public List<String[]> readAllData(String filename, int sheetIndex) throws IOException, InvalidFormatException {
        Path filePath = uploadedFiles.get(filename);
        if (filePath == null) {
            throw new RuntimeException("文件不存在: " + filename);
        }

        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return readExcelAllData(filePath, sheetIndex);
        } else if (lowerName.endsWith(".csv")) {
            return readCsvAllData(filePath);
        } else {
            throw new RuntimeException("不支持的文件格式");
        }
    }

    private List<String[]> readExcelAllData(Path filePath, int sheetIndex) throws IOException, InvalidFormatException {
        List<String[]> data = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            Sheet sheet = requireSheet(workbook, sheetIndex);
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

    public Path ensureStandardCsv(String filename, int sheetIndex) throws IOException, InvalidFormatException {
        Path inputPath = uploadedFiles.get(filename);
        if (inputPath == null) {
            throw new RuntimeException("文件不存在或已过期: " + filename);
        }

        Path uploadDir = Paths.get(appConfig.getUploadTempPath()).toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);

        String outName = filename + ".sheet" + sheetIndex + ".standard.csv";
        Path outPath = uploadDir.resolve(outName).toAbsolutePath().normalize();
        if (!outPath.startsWith(uploadDir)) {
            throw new RuntimeException("非法输出路径: " + outPath);
        }

        Path tmpPath = uploadDir.resolve(outName + ".tmp." + UUID.randomUUID()).toAbsolutePath().normalize();
        if (!tmpPath.startsWith(uploadDir)) {
            throw new RuntimeException("非法临时输出路径: " + tmpPath);
        }

        String lowerName = filename.toLowerCase();
        try {
            if (lowerName.endsWith(".csv")) {
                try (CSVReader reader = createCsvReader(inputPath);
                     Writer writer = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                    String[] header = reader.readNext();
                    if (header == null) {
                        throw new RuntimeException("CSV文件为空");
                    }
                    stripBomInPlace(header);

                    if (header.length <= 0) {
                        throw new RuntimeException("CSV表头为空或没有任何列");
                    }
                    Set<String> seen = new HashSet<>(header.length * 2);
                    for (int c = 0; c < header.length; c++) {
                        String h = header[c];
                        String trimmed = h == null ? "" : h.trim();
                        if (trimmed.isBlank()) {
                            throw new RuntimeException("CSV表头包含空列名（第 " + (c + 1) + " 列）");
                        }
                        if (!seen.add(trimmed)) {
                            throw new RuntimeException("CSV表头包含重复列名（区分大小写）: " + trimmed);
                        }
                        header[c] = trimmed;
                    }

                    int expectedLen = header.length;
                    CsvStandardizer.writeRow(writer, header, expectedLen);

                    String[] line;
                    while ((line = reader.readNext()) != null) {
                        CsvStandardizer.writeRow(writer, line, expectedLen);
                    }
                } catch (CsvException e) {
                    throw new IOException("CSV解析失败: " + e.getMessage(), e);
                }
                assertStandardCsvMatchesExpectedRowScale(lowerName, filename, sheetIndex, tmpPath);
                moveIntoPlace(tmpPath, outPath);
                return outPath;
            }

            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                ParseResult cached = getParseResult(filename);
                if (cached != null && cached.getSheets() != null
                        && sheetIndex >= 0 && sheetIndex < cached.getSheets().size()) {
                    SheetSummary pick = cached.getSheets().get(sheetIndex);
                    if (pick.getRowCount() <= 0) {
                        throw new RuntimeException(
                                "所选工作表（索引 " + sheetIndex + "，「" + pick.getName() + "」）无数据行（仅有表头或空表）。请在界面选择包含数据的工作表后再导入。");
                    }
                }

                try (Workbook workbook = WorkbookFactory.create(inputPath.toFile());
                     Writer writer = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                    Sheet sheet = requireSheet(workbook, sheetIndex);
                    Row headerRow = sheet.getRow(0);
                    if (headerRow == null) {
                        throw new RuntimeException("Excel该工作表为空或没有表头");
                    }

                    int lastCellNum = headerRow.getLastCellNum();
                    if (lastCellNum <= 0) {
                        throw new RuntimeException("Excel表头为空或没有任何列");
                    }
                    String[] header = new String[lastCellNum];
                    Set<String> seen = new HashSet<>(lastCellNum * 2);
                    for (int c = 0; c < lastCellNum; c++) {
                        String h = getCellValueAsString(headerRow.getCell(c));
                        String trimmed = h == null ? "" : h.trim();
                        if (trimmed.isBlank()) {
                            throw new RuntimeException("Excel表头包含空列名（第 " + (c + 1) + " 列）");
                        }
                        if (!seen.add(trimmed)) {
                            throw new RuntimeException("Excel表头包含重复列名: " + trimmed);
                        }
                        header[c] = trimmed;
                    }
                    CsvStandardizer.writeRow(writer, header, lastCellNum);

                    for (Row row : sheet) {
                        if (row.getRowNum() == 0) {
                            continue;
                        }
                        String[] fields = new String[lastCellNum];
                        for (int c = 0; c < lastCellNum; c++) {
                            Cell cell = row.getCell(c);
                            fields[c] = getCellValueAsString(cell);
                        }
                        CsvStandardizer.writeRow(writer, fields, lastCellNum);
                    }

                }
                assertStandardCsvMatchesExpectedRowScale(lowerName, filename, sheetIndex, tmpPath);
                moveIntoPlace(tmpPath, outPath);
                return outPath;
            }

            throw new RuntimeException("不支持的文件格式: " + filename);
        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {
                // best-effort cleanup only
            }
        }
    }

    /**
     * If parse phase recorded many data rows but the materialized standard CSV is tiny, something is inconsistent
     * (wrong sheet index, sparse Excel vs expectations, etc.) — fail fast instead of producing a misleading LOAD file.
     */
    private void assertStandardCsvMatchesExpectedRowScale(String lowerName, String filename, int sheetIndex, Path tmpPath)
            throws IOException {
        long sz = Files.size(tmpPath);
        if (sz >= 8192) {
            return;
        }
        ParseResult pr = getParseResult(filename);
        if (pr == null) {
            return;
        }
        int expected = -1;
        if (lowerName.endsWith(".csv")) {
            expected = pr.getRowCount();
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            if (pr.getSheets() != null && sheetIndex >= 0 && sheetIndex < pr.getSheets().size()) {
                expected = pr.getSheets().get(sheetIndex).getRowCount();
            } else {
                expected = pr.getRowCount();
            }
        }
        if (expected <= 500) {
            return;
        }
        throw new RuntimeException(
                "生成的标准 CSV 过小（约 " + sz + " 字节），但解析阶段记录约有 " + expected
                        + " 行数据。请确认：① 多工作表时「工作表索引」是否与含数据的表一致；② Excel 若为大量空行/稀疏存储，请另存为 UTF-8 CSV 再导入。");
    }

    private void moveIntoPlace(Path tmpPath, Path outPath) throws IOException {
        try {
            Files.move(tmpPath, outPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmpPath, outPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
