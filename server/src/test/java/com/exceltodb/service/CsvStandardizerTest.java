package com.exceltodb.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CsvStandardizerTest {
    @Test
    void writesHeaderAndDoublesQuotes_rfc4180_andPreservesNewlines() {
        String csv = CsvStandardizer.toCsv(
                List.of("a", "b", "c"),
                List.of(
                        new String[]{"x", "he\"llo", "line1\nline2"},
                        new String[]{"", null, "z"}
                )
        );

        assertEquals(
                "\"a\",\"b\",\"c\"\n" +
                "\"x\",\"he\"\"llo\",\"line1\nline2\"\n" +
                "\"\",\"\",\"z\"\n",
                csv
        );
    }

    @Test
    void escapesCommasAndQuoteOnlyField_andNullBecomesEmpty() {
        String csv = CsvStandardizer.toCsv(
                List.of("a", "b", "c"),
                List.<String[]>of(
                        new String[]{"a,b", "\"", null}
                )
        );

        assertEquals(
                "\"a\",\"b\",\"c\"\n" +
                "\"a,b\",\"\"\"\",\"\"\n",
                csv
        );
    }

    @Test
    void normalizesRowLength_padMissing_truncateExtra() {
        String csv = CsvStandardizer.toCsv(
                List.of("a", "b", "c"),
                List.of(
                        new String[]{"x", "y"},
                        new String[]{"1", "2", "3", "4"}
                )
        );

        assertEquals(
                "\"a\",\"b\",\"c\"\n" +
                "\"x\",\"y\",\"\"\n" +
                "\"1\",\"2\",\"3\"\n",
                csv
        );
    }

    @Test
    void toCsv_rejectsNullHeader() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> CsvStandardizer.toCsv(null, List.<String[]>of(new String[]{"x"}))
        );
        assertEquals("header must not be null", ex.getMessage());
    }

    @Test
    void toCsv_treatsNullRowsAsEmptyList() {
        String csv = CsvStandardizer.toCsv(List.of("a", "b"), null);
        assertEquals("\"a\",\"b\"\n", csv);
    }
}

