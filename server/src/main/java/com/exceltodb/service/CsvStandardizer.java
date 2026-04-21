package com.exceltodb.service;

import java.util.List;

public final class CsvStandardizer {
    private CsvStandardizer() {
    }

    public static String toCsv(List<String> header, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        writeRow(sb, header.toArray(new String[0]));
        for (String[] r : rows) {
            writeRow(sb, r);
        }
        return sb.toString();
    }

    public static void writeRow(StringBuilder sb, String[] fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(fields[i])).append('"');
        }
        sb.append('\n');
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }
}

