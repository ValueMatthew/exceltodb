package com.exceltodb.model;

import lombok.Data;

import java.util.List;

@Data
public class ValidateTableRequest {
    private String databaseId;
    private String tableName;
    private String filename;
    private Integer sheetIndex;
    private List<String> columns;
}
