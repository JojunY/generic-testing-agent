package com.example.invoice.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private static final BigDecimal DISCOUNT_FROM = new BigDecimal("100");

    public record Quote(BigDecimal amount, BigDecimal total) {}

    /** The total of an invoice: amounts of 100 or more get 10 % off. */
    @GetMapping("/quote")
    public Quote quote(@RequestParam BigDecimal amount) {
        // D13 (seeded defect): > instead of >= - an amount of exactly 100 gets no discount
        BigDecimal total = amount.compareTo(DISCOUNT_FROM) > 0
                ? amount.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP)
                : amount.setScale(2, RoundingMode.HALF_UP);
        return new Quote(amount, total);
    }
}
