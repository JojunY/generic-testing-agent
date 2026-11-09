package com.example.lending.borrow;

public record Borrow(String id, String security, long quantity, BorrowStatus status) {}
