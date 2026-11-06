package com.example.order.quote;

import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The quote ladder as server-sent events: one "quote" event per quantity tier, then the stream completes. */
@RestController
@RequestMapping("/api/quotes")
public class QuoteStreamController {
    static final int[] TIERS = {1, 5, 10, 20};

    private final QuoteController quotes;

    public QuoteStreamController(QuoteController quotes) {
        this.quotes = quotes;
    }

    @GetMapping(path = "/{sku}/ladder", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ladder(@PathVariable String sku, @RequestParam BigDecimal unitPrice) throws IOException {
        SseEmitter emitter = new SseEmitter();
        for (int i = 0; i < TIERS.length - 1; i++) { // seeded defect D18: the last tier is never sent
            emitter.send(SseEmitter.event()
                    .name("quote")
                    .data(quotes.requestQuote(new QuoteRequest(sku, TIERS[i], unitPrice)), MediaType.APPLICATION_JSON));
        }
        emitter.complete();
        return emitter;
    }
}
