package com.exceltodb.model;

import lombok.Data;

@Data
public class DatabaseInfo {
    private String id;
    private String name;
    private String host;
    private int port;
    private String username;
    private String password;
    private String database;
}
