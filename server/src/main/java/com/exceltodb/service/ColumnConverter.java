package com.exceltodb.service;

import java.sql.PreparedStatement;

@FunctionalInterface
public interface ColumnConverter {
    void bind(PreparedStatement ps, int paramIndex, String raw, int rowIndex, int colIndex, String columnName);
}

