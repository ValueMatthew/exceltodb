package com.exceltodb.model;

import lombok.Data;

@Data
public class SheetSummary {
    private int index;
    private String name;
    private int rowCount;
}
