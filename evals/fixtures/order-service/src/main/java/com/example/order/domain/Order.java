package com.example.order.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record Order(UUID id, String customerId, BigDecimal subtotal, BigDecimal discount, BigDecimal total, OrderStatus status) {
    public Order withStatus(OrderStatus next) {
        return new Order(id, customerId, subtotal, discount, total, next);
    }
}
