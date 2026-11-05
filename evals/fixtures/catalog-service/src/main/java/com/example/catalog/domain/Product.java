package com.example.catalog.domain;

import java.math.BigDecimal;

public record Product(String id, String name, BigDecimal price, int stock) {
    public Product withStock(int stock) {
        return new Product(id, name, price, stock);
    }
}
