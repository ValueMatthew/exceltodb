package com.exceltodb.model;

import lombok.Data;
import java.util.List;

@Data
public class CreateTableRequest {
    private String databaseId;
    private String tableName;
    private List<String> columns;
    private String filename;
}
