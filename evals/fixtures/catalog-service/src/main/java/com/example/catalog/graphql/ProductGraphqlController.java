package com.example.catalog.graphql;

import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductRepository;
import java.math.BigDecimal;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
public class ProductGraphqlController {
    private final ProductRepository repository;

    public ProductGraphqlController(ProductRepository repository) {
        this.repository = repository;
    }

    @QueryMapping
    public Mono<Product> product(@Argument String id) {
        return repository.findById(id);
    }

    @QueryMapping
    public Flux<Product> productsUpTo(@Argument double maxPrice) {
        BigDecimal max = BigDecimal.valueOf(maxPrice);
        // SEEDED DEFECT D11: < instead of <=, so a product that costs exactly the maximum is left out
        return repository.findAll().filter(p -> p.price().compareTo(max) < 0);
    }
}
