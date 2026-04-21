package com.exceltodb.service;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        if (header == null) throw new IllegalArgumentException("header must not be null");
        StringBuilder sb = new StringBuilder();
        int expectedLen = header.size();
        writeRow(sb, header.toArray(new String[0]), expectedLen);
        if (rows != null) {
            for (String[] r : rows) {
                writeRow(sb, r, expectedLen);
            }
        }
        return sb.toString();
    }

    /**
     * Writes a single RFC4180-style CSV row.
     *
     * <p>This overload uses {@code fields.length} as the expected column count. Prefer
     * {@link #writeRow(StringBuilder, String[], int)} when you want consistent column counts across
     * rows (padding/truncation).</p>
     */
    public static void writeRow(StringBuilder sb, String[] fields) {
        writeRow(sb, fields, fields == null ? 0 : fields.length);
    }

    /**
     * Writes a single RFC4180-style CSV row with a fixed expected column count.
     *
     * <p>If {@code fields} has fewer elements than {@code expectedLen}, missing columns are
     * emitted as empty strings. If {@code fields} has more elements, extras are ignored.</p>
     */
    public static void writeRow(StringBuilder sb, String[] fields, int expectedLen) {
        writeRow((Appendable) sb, fields, expectedLen);
    }

    public static void writeRow(Appendable out, String[] fields, int expectedLen) {
        String[] normalized = normalizeRow(fields, expectedLen);
        try {
            for (int i = 0; i < expectedLen; i++) {
                if (i > 0) out.append(',');
                out.append('"').append(escape(normalized[i])).append('"');
            }
            out.append('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

