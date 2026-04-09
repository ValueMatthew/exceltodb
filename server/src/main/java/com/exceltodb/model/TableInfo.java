package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class TableInfo {
    private String name;
    private int columnCount;
    private String primaryKey;
    private List<String> columns;
}
