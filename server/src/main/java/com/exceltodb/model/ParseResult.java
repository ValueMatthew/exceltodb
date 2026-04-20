package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class ParseResult {
    private String filename;
    private String sheetName;
    private int rowCount;
    private List<String> columns;
    /** 0-based index of the sheet used for columns/rowCount (default 0). */
    private int sheetIndex;
    /** Excel only: all sheets; null or empty for CSV. */
    private List<SheetSummary> sheets;
}
