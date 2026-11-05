package com.example.order.domain;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Repository
public class OrderRepository {
    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();
    private final Map<UUID, Order> archived = new ConcurrentHashMap<>();

    public Order save(Order order) {
        orders.put(order.id(), order);
        return order;
    }

    public Optional<Order> findById(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> findAll() {
        return List.copyOf(orders.values());
    }

    public int deleteWhere(Predicate<Order> condition) {
        List<Order> matching = orders.values().stream().filter(condition).toList();
        matching.forEach(o -> orders.remove(o.id()));
        return matching.size();
    }

    /** Moves matching orders to the archive. */
    public int archiveWhere(Predicate<Order> condition) {
        List<Order> matching = orders.values().stream().filter(condition).toList();
        matching.forEach(o -> archived.put(o.id(), orders.remove(o.id())));
        return matching.size();
    }

    public boolean isArchived(UUID id) {
        return archived.containsKey(id);
    }
}
