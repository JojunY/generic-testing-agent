package com.example.lending.borrow;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reserves lendable shares at inventory-service; the circuit breaker keeps a slow inventory from blocking bookings. */
@Component
public class InventoryClient {
    private final RestClient http;

    public InventoryClient(
            RestClient.Builder builder,
            @Value("${inventory.url}") String url,
            @Value("${inventory.api-key}") String apiKey) {
        this.http = builder.baseUrl(url).defaultHeader("X-Api-Key", apiKey).build();
    }

    @CircuitBreaker(name = "inventory", fallbackMethod = "inventoryDown")
    public Reservation reserve(String security, long quantity) {
        return http.post()
                .uri("/reservations")
                .body(Map.of("security", security, "quantity", quantity))
                .retrieve()
                .body(Reservation.class);
    }

    Reservation inventoryDown(String security, long quantity, Throwable cause) {
        return new Reservation(true); // seeded defect D19: an unreachable inventory books the borrow anyway
    }
}
