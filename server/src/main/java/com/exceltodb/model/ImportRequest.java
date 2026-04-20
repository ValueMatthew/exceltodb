package com.exceltodb.model;

import lombok.Data;

@Data
public class ImportRequest {
    private String filename;
    private String databaseId;
    private String tableName;
    private String importMode;  // INCREMENTAL or TRUNCATE
    private String conflictStrategy;  // ERROR, UPDATE, IGNORE
    /** Excel sheet index (0-based); null defaults to 0. Ignored for CSV. */
    private Integer sheetIndex;
}
