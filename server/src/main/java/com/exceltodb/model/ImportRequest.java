package com.exceltodb.model;

import lombok.Data;

@Data
public class ImportRequest {
    private String filename;
    private String databaseId;
    private String tableName;
    private String importMode;  // INCREMENTAL or TRUNCATE
    private String conflictStrategy;  // ERROR, UPDATE, IGNORE
}
