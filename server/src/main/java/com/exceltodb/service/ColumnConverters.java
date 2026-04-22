package com.exceltodb.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ColumnConverters {
    private ColumnConverters() {
    }

    public static ColumnConverter forMySqlType(String dataTypeLower, String columnTypeLower, boolean nullable, Integer precision, Integer scale) {
        String dt = (dataTypeLower == null ? "" : dataTypeLower.trim().toLowerCase(Locale.ROOT));
        String ct = (columnTypeLower == null ? "" : columnTypeLower.trim().toLowerCase(Locale.ROOT));

        return switch (dt) {
            case "tinyint", "smallint", "int", "integer", "bigint" -> bindLong(nullable, dt);
            case "decimal", "numeric" -> bindBigDecimal(nullable, dt);
            case "char", "varchar", "text", "mediumtext", "longtext" -> bindString(nullable, dt);
            case "datetime", "timestamp" -> bindTimestamp(nullable, dt);
            case "date" -> bindDate(nullable, dt);
            case "boolean" -> bindBoolean(nullable, dt);
            default -> unsupported(dt, ct);
        };
    }

    private static ColumnConverter unsupported(String dataTypeLower, String columnTypeLower) {
        return (ps, idx, raw, row, col, name) -> {
            throw new ImportConversionException("不支持的列类型: " + (dataTypeLower == null ? "" : dataTypeLower)
                    + (columnTypeLower == null || columnTypeLower.isBlank() ? "" : (" (" + columnTypeLower + ")")));
        };
    }

    private static ColumnConverter bindString(boolean nullable, String typeLabel) {
        return (ps, idx, raw, row, col, name) -> {
            try {
                if (raw == null || raw.isEmpty()) {
                    if (nullable) {
                        ps.setObject(idx, null);
                        return;
                    }
                    throw fail(row, col, name, typeLabel, raw);
                }
                ps.setString(idx, raw);
            } catch (ImportConversionException e) {
                throw e;
            } catch (Exception e) {
                throw fail(row, col, name, typeLabel, raw, e);
            }
        };
    }

    private static ColumnConverter bindLong(boolean nullable, String typeLabel) {
        return (ps, idx, raw, row, col, name) -> {
            String s = raw == null ? "" : raw.trim();
            try {
                if (s.isEmpty()) {
                    if (nullable) {
                        ps.setObject(idx, null);
                        return;
                    }
                    throw fail(row, col, name, typeLabel, raw);
                }
                long v = Long.parseLong(s);
                ps.setLong(idx, v);
            } catch (ImportConversionException e) {
                throw e;
            } catch (NumberFormatException e) {
                throw fail(row, col, name, typeLabel, raw, e);
            } catch (Exception e) {
                throw fail(row, col, name, typeLabel, raw, e);
            }
        };
    }

    private static ColumnConverter bindBigDecimal(boolean nullable, String typeLabel) {
        return (ps, idx, raw, row, col, name) -> {
            String s = raw == null ? "" : raw.trim();
            try {
                if (s.isEmpty()) {
                    if (nullable) {
                        ps.setObject(idx, null);
                        return;
                    }
                    throw fail(row, col, name, typeLabel, raw);
                }
                ps.setBigDecimal(idx, new BigDecimal(s));
            } catch (ImportConversionException e) {
                throw e;
            } catch (NumberFormatException e) {
                throw fail(row, col, name, typeLabel, raw, e);
            } catch (Exception e) {
                throw fail(row, col, name, typeLabel, raw, e);
            }
        };
    }

    private static ColumnConverter bindBoolean(boolean nullable, String typeLabel) {
        return (ps, idx, raw, row, col, name) -> {
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            try {
                if (s.isEmpty()) {
                    if (nullable) {
                        ps.setObject(idx, null);
                        return;
                    }
                    throw fail(row, col, name, typeLabel, raw);
                }
                if ("true".equals(s) || "1".equals(s)) {
                    ps.setBoolean(idx, true);
                    return;
                }
                if ("false".equals(s) || "0".equals(s)) {
                    ps.setBoolean(idx, false);
                    return;
                }
                throw fail(row, col, name, typeLabel, raw);
            } catch (ImportConversionException e) {
                throw e;
            } catch (Exception e) {
                throw fail(row, col, name, typeLabel, raw, e);
            }
        };
    }

    private static ColumnConverter bindTimestamp(boolean nullable, String typeLabel) {
        final List<DateTimeFormatter> fmts = dateTimeFormatters();
        return (ps, idx, raw, row, col, name) -> {
            String s = raw == null ? "" : raw.trim();
            try {
                if (s.isEmpty()) {
                    if (nullable) {
                        ps.setObject(idx, null);
                        return;
                    }
                    throw fail(row, col, name, typeLabel, raw);
                }
                LocalDateTime dt = parseLocalDateTime(s, fmts);
                ps.setTimestamp(idx, Timestamp.valueOf(dt));
            } catch (Exception e) {
                throw fail(row, col, name, typeLabel, raw, e);
            }
        };
    }

    private static ColumnConverter bindDate(boolean nullable, String typeLabel) {
        final List<DateTimeFormatter> fmts = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd")
        );
        return (ps, idx, raw, row, col, name) -> {
            String s = raw == null ? "" : raw.trim();
            try {
                if (s.isEmpty()) {
                    if (nullable) {
                        ps.setObject(idx, null);
                        return;
                    }
                    throw fail(row, col, name, typeLabel, raw);
                }
                LocalDate d = parseLocalDate(s, fmts);
                ps.setDate(idx, Date.valueOf(d));
            } catch (Exception e) {
                throw fail(row, col, name, typeLabel, raw, e);
            }
        };
    }

    private static ImportConversionException fail(int rowIndex, int colIndex, String columnName, String expectedType, String raw) {
        return new ImportConversionException(formatFail(rowIndex, colIndex, columnName, expectedType, raw));
    }

    private static ImportConversionException fail(int rowIndex, int colIndex, String columnName, String expectedType, String raw, Throwable cause) {
        return new ImportConversionException(formatFail(rowIndex, colIndex, columnName, expectedType, raw), cause);
    }

    private static String formatFail(int rowIndex, int colIndex, String columnName, String expectedType, String raw) {
        String v = raw == null ? "" : raw;
        return "第 " + rowIndex + " 行，第 " + colIndex + " 列（" + (columnName == null ? "" : columnName)
                + "）解析失败：期望 " + (expectedType == null ? "" : expectedType) + "，实际值='" + v + "'";
    }

    private static List<DateTimeFormatter> dateTimeFormatters() {
        List<DateTimeFormatter> fmts = new ArrayList<>();
        fmts.add(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        fmts.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        fmts.add(DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"));
        fmts.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
        fmts.add(DateTimeFormatter.ofPattern("yyyy/M/d H:m"));
        fmts.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return fmts;
    }

    private static LocalDateTime parseLocalDateTime(String s, List<DateTimeFormatter> fmts) {
        for (DateTimeFormatter f : fmts) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new DateTimeParseException("无法解析日期时间", s, 0);
    }

    private static LocalDate parseLocalDate(String s, List<DateTimeFormatter> fmts) {
        for (DateTimeFormatter f : fmts) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new DateTimeParseException("无法解析日期", s, 0);
    }
}

