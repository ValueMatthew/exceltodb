package com.exceltodb.service;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ColumnConvertersTest {

    @Test
    void intConverter_rejectsInvalidNumber() {
        ColumnConverter c = ColumnConverters.forMySqlType("int", "int", false, null, null);
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(ImportConversionException.class, () -> c.bind(ps, 1, "abc", 1, 1, "id"));
    }

    @Test
    void decimalConverter_rejectsInvalidDecimal() {
        ColumnConverter c = ColumnConverters.forMySqlType("decimal", "decimal(10,2)", false, 10, 2);
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(ImportConversionException.class, () -> c.bind(ps, 1, "1.2.3", 1, 2, "price"));
    }

    @Test
    void datetimeConverter_rejectsInvalidDateTime() {
        ColumnConverter c = ColumnConverters.forMySqlType("datetime", "datetime", false, null, null);
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(ImportConversionException.class, () -> c.bind(ps, 1, "not-a-time", 9, 3, "ts"));
    }

    @Test
    void nonNullable_emptyString_fails() {
        ColumnConverter c = ColumnConverters.forMySqlType("varchar", "varchar(10)", false, null, null);
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(ImportConversionException.class, () -> c.bind(ps, 1, "", 1, 1, "name"));
    }
}

