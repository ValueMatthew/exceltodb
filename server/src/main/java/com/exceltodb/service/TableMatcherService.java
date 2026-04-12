package com.exceltodb.service;

import com.exceltodb.model.TableInfo;
import com.exceltodb.model.TableRecommendation;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TableMatcherService {

    private static final double MATCH_THRESHOLD = 0.9;

    public TableRecommendation findBestMatch(List<TableInfo> tables, List<String> excelColumns, String filename) {
        if (tables == null || tables.isEmpty() || excelColumns == null || excelColumns.isEmpty()) {
            return null;
        }

        TableRecommendation bestMatch = null;
        double bestScore = 0;

        for (TableInfo table : tables) {
            double score = calculateMatchScore(table, excelColumns);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = createRecommendation(table, excelColumns, score);
            }
        }

        // Always return the best match, frontend decides whether to show it based on score
        return bestMatch;
    }

    private double calculateMatchScore(TableInfo table, List<String> excelColumns) {
        // Get columns to exclude from matching (only columns with defaults/ON UPDATE, not primary keys)
        List<String> excludedCols = table.getExcludedColumns();
        if (excludedCols == null) excludedCols = List.of();

        // Column match rate: Excel列在表中存在的比例（排除带默认值的列）
        double columnMatchRate = calculateColumnMatchRate(excelColumns, table.getColumns(), excludedCols);

        // Score = 列匹配率 × 100%
        return columnMatchRate * 100;
    }

    private double calculateColumnMatchRate(List<String> excelColumns, List<String> tableColumns, List<String> excludedCols) {
        if (tableColumns == null || tableColumns.isEmpty()) return 0;

        // Filter out excluded columns from table columns for matching
        List<String> matchableTableCols = tableColumns.stream()
                .filter(col -> !excludedCols.contains(col))
                .toList();

        if (matchableTableCols.isEmpty()) return 0;

        int matchCount = 0;
        for (String excelCol : excelColumns) {
            String normalizedExcelCol = excelCol.toLowerCase().trim();
            for (String tableCol : matchableTableCols) {
                if (tableCol.toLowerCase().trim().equals(normalizedExcelCol)) {
                    matchCount++;
                    break;
                }
            }
        }

        return (double) matchCount / excelColumns.size();
    }

    private TableRecommendation createRecommendation(TableInfo table, List<String> excelColumns, double score) {
        TableRecommendation rec = new TableRecommendation();
        rec.setTableName(table.getName());
        rec.setScore((int) Math.round(score));
        rec.setColumnCount(table.getColumnCount());
        rec.setPrimaryKey(table.getPrimaryKey());
        rec.setColumns(table.getColumns());
        rec.setMatchedColumns(findMatchedColumns(excelColumns, table.getColumns()));

        return rec;
    }

    private List<String> findMatchedColumns(List<String> excelColumns, List<String> tableColumns) {
        return excelColumns.stream()
                .filter(col -> tableColumns.stream()
                        .anyMatch(tc -> tc.equalsIgnoreCase(col.trim())))
                .toList();
    }

}
