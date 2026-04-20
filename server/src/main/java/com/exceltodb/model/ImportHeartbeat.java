package com.exceltodb.model;

import lombok.Data;

@Data
public class ImportHeartbeat {
    private String requestId;
    private ImportStatus status;
    private ImportStage stage;
    private long updatedAt;
    private long processedRows;
    private String message;
}

