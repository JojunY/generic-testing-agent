package com.example.order.quote;

import java.math.BigDecimal;

public record Quote(String sku, int quantity, BigDecimal total) {}
