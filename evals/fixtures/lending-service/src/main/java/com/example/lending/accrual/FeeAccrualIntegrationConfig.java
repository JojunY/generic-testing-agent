package com.example.lending.accrual;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.dsl.IntegrationFlow;

/** Daily lending fee: quantity x price x rate / 360 (Act/360), rounded half-even to cents; zero-rate borrows accrue nothing. */
@Configuration
public class FeeAccrualIntegrationConfig {
    @Bean
    public QueueChannel accruals() {
        return new QueueChannel();
    }

    @Bean
    public IntegrationFlow feeAccrualFlow() {
        return IntegrationFlow.from("accrualRequests")
                .<AccrualRequest>filter(r -> r.rateBps() > 0)
                .<AccrualRequest, DailyFee>transform(r -> new DailyFee(r.borrowId(), fee(r)))
                .channel("accruals")
                .get();
    }

    static BigDecimal fee(AccrualRequest r) {
        return r.price()
                .multiply(BigDecimal.valueOf(r.quantity()))
                .multiply(BigDecimal.valueOf(r.rateBps()))
                .divide(BigDecimal.valueOf(10_000L * 365), 2, RoundingMode.HALF_EVEN); // seeded defect D21: 365, not 360
    }
}
