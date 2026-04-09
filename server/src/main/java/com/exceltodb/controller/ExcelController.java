package com.exceltodb.controller;

import com.exceltodb.model.*;
import com.exceltodb.service.DbService;
import com.exceltodb.service.ExcelParserService;
import com.exceltodb.service.ImportService;
import com.exceltodb.service.TableMatcherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ExcelController {

    private final DbService dbService;
    private final ExcelParserService excelParserService;
    private final TableMatcherService tableMatcherService;
    private final ImportService importService;

    public ExcelController(DbService dbService, ExcelParserService excelParserService,
                           TableMatcherService tableMatcherService, ImportService importService) {
        this.dbService = dbService;
        this.excelParserService = excelParserService;
        this.tableMatcherService = tableMatcherService;
        this.importService = importService;
    }

    @GetMapping("/databases")
    public ResponseEntity<List<DatabaseInfo>> getDatabases() {
        return ResponseEntity.ok(dbService.getAllDatabases());
    }

    @GetMapping("/databases/{databaseId}/test")
    public ResponseEntity<Void> testConnection(@PathVariable String databaseId) {
        boolean success = dbService.testConnection(databaseId);
        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<ParseResult> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            ParseResult result = excelParserService.parseAndSave(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/preview/{filename}")
    public ResponseEntity<PreviewResult> getPreview(@PathVariable String filename,
                                                     @RequestParam(defaultValue = "100") int maxRows) {
        try {
            PreviewResult result = excelParserService.getPreview(filename, maxRows);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/tables/{databaseId}")
    public ResponseEntity<List<TableInfo>> getTables(@PathVariable String databaseId) {
        try {
            List<TableInfo> tables = dbService.getAllTables(databaseId);
            return ResponseEntity.ok(tables);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/recommend")
    public ResponseEntity<TableRecommendation> getRecommendation(@RequestBody RecommendRequest request) {
        try {
            List<TableInfo> tables = dbService.getAllTables(request.getDatabaseId());
            PreviewResult preview = excelParserService.getPreview(request.getFilename(), 100);

            TableRecommendation recommendation = tableMatcherService.findBestMatch(
                    tables, preview.getColumns(), request.getFilename());

            if (recommendation == null) {
                return ResponseEntity.ok().build();
            }

            return ResponseEntity.ok(recommendation);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/import")
    public ResponseEntity<ImportResult> importData(@RequestBody ImportRequest request) {
        try {
            ImportResult result = importService.importData(request);
            if (result.isSuccess()) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(500).body(result);
            }
        } catch (Exception e) {
            ImportResult errorResult = new ImportResult();
            errorResult.setSuccess(false);
            errorResult.setMessage(e.getMessage());
            return ResponseEntity.status(500).body(errorResult);
        }
    }

    @PostMapping("/create-table")
    public ResponseEntity<String> createTable(@RequestBody CreateTableRequest request) {
        try {
            String result = importService.createTable(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // Inner class for recommendation request
    public static class RecommendRequest {
        private String databaseId;
        private String filename;
        private List<String> columns;

        public String getDatabaseId() { return databaseId; }
        public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns; }
    }
}
