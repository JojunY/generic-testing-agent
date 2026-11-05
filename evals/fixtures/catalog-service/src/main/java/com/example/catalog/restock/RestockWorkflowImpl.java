package com.example.catalog.restock;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class RestockWorkflowImpl implements RestockWorkflow {
    private final RestockActivities activities = Workflow.newActivityStub(
            RestockActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public String restock(String productId, int quantity) {
        String supplierOrder = activities.orderFromSupplier(productId, quantity);
        try {
            activities.addStock(productId, quantity);
            return "restocked";
        } catch (ActivityFailure failure) {
            // D14 (seeded defect): the supplier order is not cancelled, so the stock is paid for but never booked
            return "cancelled";
        }
    }
}
