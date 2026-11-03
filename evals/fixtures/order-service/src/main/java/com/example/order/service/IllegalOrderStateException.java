package com.example.order.service;

import com.example.order.domain.OrderStatus;

public class IllegalOrderStateException extends RuntimeException {
    public IllegalOrderStateException(OrderStatus status) {
        super("Order in status " + status + " cannot be changed");
    }
}
