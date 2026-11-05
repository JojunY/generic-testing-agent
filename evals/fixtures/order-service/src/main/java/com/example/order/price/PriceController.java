package com.example.order.price;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prices")
public class PriceController {
    private final PriceService prices;

    public PriceController(PriceService prices) {
        this.prices = prices;
    }

    @GetMapping("/{sku}")
    public Map<String, BigDecimal> getPrice(@PathVariable String sku) {
        return Map.of("price", prices.price(sku));
    }

    @PutMapping("/{sku}")
    public Map<String, BigDecimal> updatePrice(@PathVariable String sku, @RequestBody Map<String, BigDecimal> body) {
        return Map.of("price", prices.update(sku, body.get("price")));
    }
}
