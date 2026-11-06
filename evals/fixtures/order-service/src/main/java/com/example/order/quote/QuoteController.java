package com.example.order.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/** Live quotes over STOMP: a client sends a request to /app/quote and every subscriber of /topic/quotes gets the quote. */
@Controller
public class QuoteController {
    static final BigDecimal BULK_DISCOUNT = new BigDecimal("0.90");

    @MessageMapping("/quote")
    @SendTo("/topic/quotes")
    public Quote requestQuote(QuoteRequest request) {
        BigDecimal total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));
        if (request.quantity() > 10) { // seeded defect D17: the rule says 10 or more
            total = total.multiply(BULK_DISCOUNT);
        }
        return new Quote(request.sku(), request.quantity(), total.setScale(2, RoundingMode.HALF_UP));
    }
}
