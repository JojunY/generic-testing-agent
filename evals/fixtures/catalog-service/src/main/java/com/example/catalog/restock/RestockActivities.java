package com.example.catalog.restock;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface RestockActivities {
    /** Places a supplier order and returns its id. */
    String orderFromSupplier(String productId, int quantity);

    void addStock(String productId, int quantity);

    void cancelSupplierOrder(String supplierOrderId);
}
