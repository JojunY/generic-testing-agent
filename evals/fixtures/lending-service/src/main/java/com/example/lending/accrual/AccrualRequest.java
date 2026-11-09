package com.example.lending.accrual;

import java.math.BigDecimal;

/** One day of an open borrow: quantity, price per share and the fee rate in basis points per year. */
public record AccrualRequest(String borrowId, long quantity, BigDecimal price, int rateBps) {}
