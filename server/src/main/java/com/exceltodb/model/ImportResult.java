package com.exceltodb.model;

import lombok.Data;

@Data
public class ImportResult {
    private int importedRows;
    private int failedRows;
    private String message;
    private boolean success;
}
