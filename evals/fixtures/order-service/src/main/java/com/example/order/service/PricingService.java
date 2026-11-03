package com.example.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PricingService {
    private final BigDecimal threshold;
    private final BigDecimal rate;

    public PricingService(@Value("${order.discount.threshold:100.00}") BigDecimal threshold,
                          @Value("${order.discount.rate:0.10}") BigDecimal rate) {
        this.threshold = threshold;
        this.rate = rate;
    }

    /** Orders of at least the threshold get the discount rate. */
    public BigDecimal discount(BigDecimal subtotal) {
        // SEEDED DEFECT D1: uses > instead of >=, so an order of exactly 100.00 gets no discount
        if (subtotal.compareTo(threshold) > 0) {
            return subtotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2);
    }
}
