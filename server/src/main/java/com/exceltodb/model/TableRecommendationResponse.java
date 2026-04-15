package com.exceltodb.model;

import lombok.Data;

import java.util.List;

@Data
public class TableRecommendationResponse {
    private int threshold;
    private int topScore;
    private List<TableRecommendation> recommendations;
}

