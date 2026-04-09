package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class TableRecommendation {
    private String tableName;
    private int score;
    private int columnCount;
    private String primaryKey;
    private List<String> matchedColumns;
    private List<String> columns;
}
