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
            validateCsvHeader(header, "CSV解析失败");

            int rowCount = 0;
            String[] row;
            while ((row = reader.readNext()) != null) {
                rowCount++;
                validateCsvRowShape(filePath, header.length, row, rowCount + 1, "CSV解析失败");
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
            validateCsvHeader(header, "CSV预览失败");
            result.setColumns(Arrays.asList(header));

            List<Map<String, Object>> previewRows = new ArrayList<>();
            int totalRows = 0;
            String[] line;
            while ((line = reader.readNext()) != null) {
                totalRows++;
                validateCsvRowShape(filePath, result.getColumns().size(), line, totalRows + 1, "CSV预览失败");
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
                validateCsvHeader(all.get(0), "CSV读取失败");
                int expected = all.get(0).length;
                for (int i = 1; i < all.size(); i++) {
                    validateCsvRowShape(filePath, expected, all.get(i), i + 1, "CSV读取失败");
                }
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

    private void validateCsvHeader(String[] header, String action) {
        if (header == null || header.length == 0) return;
        for (int i = 0; i < header.length; i++) {
            String col = header[i] == null ? "" : header[i].trim();
            if (col.isEmpty()) {
                throw new RuntimeException(action + "：CSV 表头第 " + (i + 1) + " 列为空。常见原因：表头行末尾多了一个分隔符（例如以逗号结尾）。");
            }
        }
        Set<String> seen = new HashSet<>();
        for (String col : header) {
            String key = (col == null ? "" : col.trim()).toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                throw new RuntimeException(action + "：CSV 表头存在重复列名：" + col + "。请检查导出配置，确保列名唯一。");
            }
        }
    }

    private void validateCsvRowShape(Path filePath, int expectedCols, String[] row, int lineNo1Based, String action) {
        int actualCols = row == null ? 0 : row.length;
        if (actualCols == expectedCols) return;

        String preview = buildCsvRowPreview(row, 6, 180);
        String rawLinePreview = buildRawLinePreview(filePath, lineNo1Based, 260);
        CsvLineSignals signals = analyzeRawCsvLine(rawLinePreview);
        String hint = buildCsvShapeHint(expectedCols, actualCols, signals);

        throw new RuntimeException(
                action + "：第 " + lineNo1Based + " 行解析异常。\n" +
                        "- 期望列数（表头）：" + expectedCols + "\n" +
                        "- 实际列数（该行）：" + actualCols + "\n" +
                        "- 该行内容预览（解析后前几列）： " + preview + "\n" +
                        "- 该行原始文本片段： " + rawLinePreview + "\n" +
                        "- 符号统计：逗号(,)=" + signals.commas + "；分号(;)= " + signals.semicolons + "；TAB= " + signals.tabs +
                        "；双引号(\")=" + signals.doubleQuotes + "\n" +
                        hint + "\n" +
                        "建议：用 Excel 打开另存为标准 CSV（逗号分隔）或直接上传 Excel（.xlsx）。"
        );
    }

    private String buildCsvShapeHint(int expectedCols, int actualCols, CsvLineSignals signals) {
        String likelyDelimiter = detectLikelyDelimiter(signals);
        StringBuilder sb = new StringBuilder();
        sb.append("初步判断：");
        if (likelyDelimiter != null && !",".equals(likelyDelimiter)) {
            sb.append("你的 CSV 很可能不是逗号分隔，而是使用 ").append(likelyDelimiter).append(" 作为分隔符；但当前系统按英文逗号 , 解析。");
        } else if (actualCols > expectedCols) {
            sb.append("该行被拆成更多列，常见原因是某个字段里包含英文逗号 , 但没有用双引号包裹（应写成 \"a,b\"）。");
        } else if (actualCols < expectedCols) {
            sb.append("该行列数变少，常见原因是该行缺少分隔符，或存在未闭合的引号导致解析器把后续内容吞进同一列。");
        } else {
            sb.append("CSV 格式/引号转义不规范。");
        }
        if (signals.doubleQuotes % 2 == 1) {
            sb.append(" 另外：该行双引号数量为奇数，疑似有引号未闭合。");
        }
        sb.append("（CSV 字段内若包含逗号/换行，必须用双引号包裹）");
        return sb.toString();
    }

    private String buildCsvRowPreview(String[] row, int maxCells, int maxChars) {
        if (row == null) return "(空行)";
        StringBuilder sb = new StringBuilder();
        int cells = Math.min(maxCells, row.length);
        for (int i = 0; i < cells; i++) {
            if (i > 0) sb.append(" | ");
            String v = row[i] == null ? "" : row[i];
            v = v.replace("\r", "\\r").replace("\n", "\\n").trim();
            if (v.length() > 60) v = v.substring(0, 60) + "...";
            sb.append("[").append(v).append("]");
        }
        if (row.length > cells) sb.append(" | ...（共 ").append(row.length).append(" 列）");
        String out = sb.toString();
        if (out.length() > maxChars) out = out.substring(0, maxChars) + "...";
        return out;
    }

    private String buildRawLinePreview(Path filePath, int lineNo1Based, int maxChars) {
        if (filePath == null) return "(无法获取原始行)";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(filePath), detectTextCharset(filePath)))) {
            String line;
            int current = 0;
            while ((line = br.readLine()) != null) {
                current++;
                if (current == lineNo1Based) {
                    String v = line;
                    if (v.length() > maxChars) v = v.substring(0, maxChars) + "...";
                    return v;
                }
            }
            return "(未找到原始行：" + lineNo1Based + ")";
        } catch (Exception e) {
            return "(读取原始行失败：" + (e.getMessage() == null ? "unknown" : e.getMessage()) + ")";
        }
    }

    private static class CsvLineSignals {
        int commas;
        int semicolons;
        int tabs;
        int doubleQuotes;
    }

    private CsvLineSignals analyzeRawCsvLine(String rawLinePreview) {
        CsvLineSignals s = new CsvLineSignals();
        if (rawLinePreview == null) return s;
        for (int i = 0; i < rawLinePreview.length(); i++) {
            char c = rawLinePreview.charAt(i);
            if (c == ',') s.commas++;
            else if (c == ';') s.semicolons++;
            else if (c == '\t') s.tabs++;
            else if (c == '"') s.doubleQuotes++;
        }
        int idx = 0;
        while ((idx = rawLinePreview.indexOf("\\t", idx)) >= 0) {
            s.tabs++;
            idx += 2;
        }
        return s;
    }

    private String detectLikelyDelimiter(CsvLineSignals s) {
        if (s == null) return null;
        // Heuristic: if one separator appears much more than the others.
        int max = Math.max(s.commas, Math.max(s.semicolons, s.tabs));
        if (max == 0) return null;
        // Require some dominance to avoid false positives.
        if (max == s.tabs && s.tabs >= s.commas + 3 && s.tabs >= s.semicolons + 3) return "TAB(\\t)";
        if (max == s.semicolons && s.semicolons >= s.commas + 3 && s.semicolons >= s.tabs + 3) return "分号(;) ";
        if (max == s.commas) return ","; // default
        return null;
    }

    public Path getFilePath(String filename) {
        return uploadedFiles.get(filename);
    }

    public ParseResult getParseResult(String filename) {
        return parseCache.get(filename);
    }
}
