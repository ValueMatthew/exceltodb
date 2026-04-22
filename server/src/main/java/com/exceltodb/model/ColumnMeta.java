package com.exceltodb.model;

import lombok.Data;

@Data
public class ColumnMeta {
    private String name;          // COLUMN_NAME
    private String dataType;      // DATA_TYPE (lowercase)
    private String columnType;    // COLUMN_TYPE (lowercase, e.g. decimal(10,2), tinyint(1))
    private boolean nullable;     // IS_NULLABLE == YES
    private Integer precision;    // NUMERIC_PRECISION (nullable)
    private Integer scale;        // NUMERIC_SCALE (nullable)
}

