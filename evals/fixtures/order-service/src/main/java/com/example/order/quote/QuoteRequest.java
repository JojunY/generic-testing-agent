package com.example.order.quote;

import java.math.BigDecimal;

public record QuoteRequest(String sku, int quantity, BigDecimal unitPrice) {}
