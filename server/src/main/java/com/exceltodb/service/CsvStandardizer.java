package com.exceltodb.service;

import java.util.List;

public final class CsvStandardizer {
    private CsvStandardizer() {
    }

    /**
     * Produces RFC4180-style CSV suitable for MySQL {@code LOAD DATA}.
     *
     * - Fields are comma-separated and always enclosed in double quotes.
     * - Double quotes inside a field are escaped by doubling them ({@code ""}).
     * - Rows are terminated with {@code \n}.
     *
     * This is intentionally compatible with using {@code LOAD DATA} with e.g.:
     * {@code FIELDS TERMINATED BY ',' ENCLOSED BY '"' ESCAPED BY '"' LINES TERMINATED BY '\n'}.
     */
    public static String toCsv(List<String> header, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        int expectedLen = header.size();
        writeRow(sb, header.toArray(new String[0]), expectedLen);
        for (String[] r : rows) {
            writeRow(sb, r, expectedLen);
        }
        return sb.toString();
    }

    public static void writeRow(StringBuilder sb, String[] fields) {
        writeRow(sb, fields, fields == null ? 0 : fields.length);
    }

    public static void writeRow(StringBuilder sb, String[] fields, int expectedLen) {
        String[] normalized = normalizeRow(fields, expectedLen);
        for (int i = 0; i < expectedLen; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(normalized[i])).append('"');
        }
        sb.append('\n');
    }

    static String[] normalizeRow(String[] fields, int expectedLen) {
        if (expectedLen < 0) throw new IllegalArgumentException("expectedLen must be >= 0");

        String[] out = new String[expectedLen];
        if (fields == null) return out;

        int n = Math.min(fields.length, expectedLen);
        if (n > 0) {
            System.arraycopy(fields, 0, out, 0, n);
        }
        return out;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }
}

