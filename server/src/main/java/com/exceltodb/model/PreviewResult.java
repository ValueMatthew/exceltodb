package com.exceltodb.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PreviewResult {
    private String filename;
    private String sheetName;
    private int totalRows;
    private List<String> columns;
    private List<Map<String, Object>> previewRows;
}
