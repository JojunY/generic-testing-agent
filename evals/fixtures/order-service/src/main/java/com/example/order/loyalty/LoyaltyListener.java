package com.example.order.loyalty;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class LoyaltyListener {
    private final LoyaltyService loyalty;

    public LoyaltyListener(LoyaltyService loyalty) {
        this.loyalty = loyalty;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onOrderCreated(OrderCreated event) {
        loyalty.award(event.customerId(), event.total());
    }
}
