package com.example.order.api;

import com.example.order.domain.Order;
import com.example.order.service.IllegalOrderStateException;
import com.example.order.service.OrderNotFoundException;
import com.example.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.customerId(), request.subtotal()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable UUID id) {
        // SEEDED DEFECT D3: an unknown id returns 200 with an empty body instead of 404
        return ResponseEntity.ok(service.find(id).orElse(null));
    }

    @PostMapping("/{id}/cancel")
    public Order cancelOrder(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public Order shipOrder(@PathVariable UUID id) {
        return service.markShipped(id);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalOrderStateException.class)
    ProblemDetail conflict(IllegalOrderStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
