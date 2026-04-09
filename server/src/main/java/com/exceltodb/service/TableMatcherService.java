package com.exceltodb.service;

import com.exceltodb.model.TableInfo;
import com.exceltodb.model.TableRecommendation;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TableMatcherService {

    private static final double MATCH_THRESHOLD = 0.3;

    public TableRecommendation findBestMatch(List<TableInfo> tables, List<String> excelColumns, String filename) {
        if (tables == null || tables.isEmpty() || excelColumns == null || excelColumns.isEmpty()) {
            return null;
        }

        TableRecommendation bestMatch = null;
        double bestScore = 0;

        String fileBaseName = getBaseName(filename);

        for (TableInfo table : tables) {
            double score = calculateMatchScore(table, excelColumns, fileBaseName);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = createRecommendation(table, excelColumns, score);
            }
        }

        if (bestScore < MATCH_THRESHOLD) {
            return null;
        }

        return bestMatch;
    }

    private double calculateMatchScore(TableInfo table, List<String> excelColumns, String filename) {
        // Table name similarity (weight: 30%)
        double nameSimilarity = calculateNameSimilarity(filename, table.getName());

        // Column match rate (weight: 50%)
        double columnMatchRate = calculateColumnMatchRate(excelColumns, table.getColumns());

        // Primary key bonus (weight: 20%)
        double pkBonus = hasMatchingPrimaryKey(table.getPrimaryKey(), excelColumns) ? 0.2 : 0;

        // Total score
        return (nameSimilarity * 0.3 + columnMatchRate * 0.5 + pkBonus) * 100;
    }

    private double calculateNameSimilarity(String name1, String name2) {
        String s1 = normalize(name1);
        String s2 = normalize(name2);

        if (s1.equals(s2)) return 1.0;
        if (s1.contains(s2) || s2.contains(s1)) return 0.8;

        return calculateJaccardSimilarity(s1, s2);
    }

    private String normalize(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replace("xlsx", "")
                .replace("xls", "")
                .replace("csv", "");
    }

    private double calculateJaccardSimilarity(String s1, String s2) {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0;

        int intersection = 0;
        for (char c : s1.toCharArray()) {
            if (s2.indexOf(c) >= 0) {
                intersection++;
            }
        }

        int union = s1.length() + s2.length() - intersection;
        return (double) intersection / union;
    }

    private double calculateColumnMatchRate(List<String> excelColumns, List<String> tableColumns) {
        if (tableColumns == null || tableColumns.isEmpty()) return 0;

        int matchCount = 0;
        for (String excelCol : excelColumns) {
            String normalizedExcelCol = excelCol.toLowerCase().trim();
            for (String tableCol : tableColumns) {
                if (tableCol.toLowerCase().trim().equals(normalizedExcelCol)) {
                    matchCount++;
                    break;
                }
            }
        }

        return (double) matchCount / excelColumns.size();
    }

    private boolean hasMatchingPrimaryKey(String primaryKey, List<String> excelColumns) {
        if (primaryKey == null || primaryKey.isEmpty()) return false;

        String normalizedPk = primaryKey.toLowerCase().trim();
        for (String col : excelColumns) {
            if (col.toLowerCase().trim().equals(normalizedPk)) {
                return true;
            }
        }
        return false;
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

    private String getBaseName(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            return filename.substring(0, dotIndex);
        }
        return filename;
    }
}
