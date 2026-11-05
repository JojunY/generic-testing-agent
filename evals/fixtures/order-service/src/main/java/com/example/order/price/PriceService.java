package com.example.order.price;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class PriceService {
    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>(Map.of("sku-1", new BigDecimal("10.00")));

    @Cacheable("prices")
    public BigDecimal price(String sku) {
        return prices.get(sku);
    }

    // SEEDED DEFECT D7: no @CacheEvict("prices"), so a cached price stays stale after an update
    public BigDecimal update(String sku, BigDecimal price) {
        prices.put(sku, price);
        return price;
    }
}
