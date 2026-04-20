package com.exceltodb.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PreviewResult {
    private String filename;
    private String sheetName;
    /** 0-based sheet index for Excel; always 0 for CSV. */
    private int sheetIndex;
    private int totalRows;
    private List<String> columns;
    private List<Map<String, Object>> previewRows;
}
