package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.domain.OrderRepository;
import com.example.order.domain.OrderStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {
    private final OrderRepository repository;
    private final PricingService pricing;

    public OrderService(OrderRepository repository, PricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    public Order create(String customerId, BigDecimal subtotal) {
        BigDecimal discount = pricing.discount(subtotal);
        return repository.save(new Order(UUID.randomUUID(), customerId, subtotal, discount, subtotal.subtract(discount), OrderStatus.CREATED));
    }

    public Optional<Order> find(UUID id) {
        return repository.findById(id);
    }

    public Order cancel(UUID id) {
        Order order = repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        // SEEDED DEFECT D2: a SHIPPED order can be cancelled; only CREATED and PAID orders should be
        if (order.status() == OrderStatus.CANCELLED) {
            throw new IllegalOrderStateException(order.status());
        }
        return repository.save(order.withStatus(OrderStatus.CANCELLED));
    }

    public Order markShipped(UUID id) {
        Order order = repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        return repository.save(order.withStatus(OrderStatus.SHIPPED));
    }
}
