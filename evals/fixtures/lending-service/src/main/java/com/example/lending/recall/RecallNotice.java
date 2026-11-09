package com.example.lending.recall;

/** A lender recalls shares of an open borrow. */
public record RecallNotice(String borrowId, long borrowedQuantity, long recalledQuantity) {}
