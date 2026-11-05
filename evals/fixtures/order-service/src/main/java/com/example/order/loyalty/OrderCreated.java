package com.example.order.loyalty;

import java.math.BigDecimal;

/** Published when an order is created. */
public record OrderCreated(String customerId, BigDecimal total) {}
