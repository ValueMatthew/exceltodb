package com.exceltodb.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CsvStandardizerTest {
    @Test
    void writesHeaderAndEscapesQuotesAndNewlines() {
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
}

