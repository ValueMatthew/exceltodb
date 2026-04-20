package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class CreateTableRequest {
    private String databaseId;
    private String tableName;
    private List<String> columns;
    private String filename;
    /** Excel sheet index (0-based); null defaults to 0. Ignored for CSV. */
    private Integer sheetIndex;
}
