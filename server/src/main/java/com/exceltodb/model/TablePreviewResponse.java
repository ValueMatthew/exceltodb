package com.exceltodb.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TablePreviewResponse {
    private String tableName;
    private List<String> columns;
    private List<Map<String, Object>> rows;
}
