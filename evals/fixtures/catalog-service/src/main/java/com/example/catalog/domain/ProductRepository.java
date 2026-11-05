package com.example.catalog.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class ProductRepository {
    private final Map<String, Product> products = new ConcurrentHashMap<>(Map.of(
            "p-1", new Product("p-1", "Kettle", new BigDecimal("30.00"), 5),
            "p-2", new Product("p-2", "Toaster", new BigDecimal("45.00"), 2),
            "p-3", new Product("p-3", "Blender", new BigDecimal("80.00"), 0)));

    public Mono<Product> findById(String id) {
        return Mono.justOrEmpty(products.get(id));
    }

    public Flux<Product> findAll() {
        return Flux.fromIterable(products.values()).sort((a, b) -> a.id().compareTo(b.id()));
    }

    public Mono<Product> save(Product product) {
        products.put(product.id(), product);
        return Mono.just(product);
    }
}
