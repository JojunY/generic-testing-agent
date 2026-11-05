package com.example.catalog.restock;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RestockWorkflow {
    /** Orders stock from the supplier and books it; returns "restocked" or "cancelled". */
    @WorkflowMethod
    String restock(String productId, int quantity);
}
