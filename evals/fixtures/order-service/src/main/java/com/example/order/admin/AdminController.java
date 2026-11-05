package com.example.order.admin;

import com.example.order.domain.OrderRepository;
import com.example.order.domain.OrderStatus;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminController {
    private final OrderRepository repository;

    public AdminController(OrderRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/purge-cancelled")
    public Map<String, Integer> purgeCancelled() {
        return Map.of("purged", repository.deleteWhere(o -> o.status() == OrderStatus.CANCELLED));
    }
}
