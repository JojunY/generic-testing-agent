package com.example.lending.accrual;

import java.math.BigDecimal;

public record DailyFee(String borrowId, BigDecimal fee) {}
