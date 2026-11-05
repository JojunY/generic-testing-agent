package com.example.order.shipping;

import com.example.order.domain.Order;
import com.example.order.domain.OrderRepository;
import com.example.order.domain.OrderStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ships paid orders; scheduling is switched on where the application runs (no @EnableScheduling in the fixture). */
@Component
public class ShippingJob {
    private final OrderRepository repository;

    public ShippingJob(OrderRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${shipping.interval-ms:60000}")
    public void shipPaidOrders() {
        for (Order order : repository.findAll()) {
            // SEEDED DEFECT D4: CREATED (unpaid) orders are shipped too; only PAID orders should be
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.CREATED) {
                repository.save(order.withStatus(OrderStatus.SHIPPED));
            }
        }
    }
}
