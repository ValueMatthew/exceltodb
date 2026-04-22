package com.exceltodb.service;

public class ImportConversionException extends RuntimeException {
    public ImportConversionException(String message) {
        super(message);
    }

    public ImportConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}

