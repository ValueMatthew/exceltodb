package com.exceltodb.model;

import lombok.Data;

@Data
public class ValidateTableResponse {
    private boolean exists;
    private int threshold;
    private int score;
    private ValidateTableReason reason;
    private TableRecommendation table;
}
