package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A developer's own unit test (not gta's): `gtaw mutate` counts the mutants it kills. */
class PricingServiceTest {
    PricingService pricing = new PricingService(new BigDecimal("100.00"), new BigDecimal("0.10"));

    @Test
    void shouldDiscountALargeOrder() {
        assertThat(pricing.discount(new BigDecimal("150.00"))).isEqualByComparingTo("15.00");
    }
}
