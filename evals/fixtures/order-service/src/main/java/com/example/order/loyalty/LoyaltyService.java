package com.example.order.loyalty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LoyaltyService {
    private final Map<String, Integer> points = new ConcurrentHashMap<>();

    public void award(String customerId, BigDecimal total) {
        // SEEDED DEFECT D5: rounds up, so every started 10.00 earns a point; only full 10.00 should
        int earned = total.divide(BigDecimal.TEN, 0, RoundingMode.UP).intValue();
        points.merge(customerId, earned, Integer::sum);
    }

    public int points(String customerId) {
        return points.getOrDefault(customerId, 0);
    }
}
