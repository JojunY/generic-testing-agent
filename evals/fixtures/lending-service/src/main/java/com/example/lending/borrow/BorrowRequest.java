package com.example.lending.borrow;

public record BorrowRequest(String clientId, String security, long quantity) {}
