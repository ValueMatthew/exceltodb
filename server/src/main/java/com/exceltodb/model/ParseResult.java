package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class ParseResult {
    private String filename;
    private String sheetName;
    private int rowCount;
    private List<String> columns;
}
