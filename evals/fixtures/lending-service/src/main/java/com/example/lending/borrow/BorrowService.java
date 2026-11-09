package com.example.lending.borrow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class BorrowService {
    private final InventoryClient inventory;
    private final Map<String, Borrow> borrows = new ConcurrentHashMap<>();

    public BorrowService(InventoryClient inventory) {
        this.inventory = inventory;
    }

    public Borrow book(BorrowRequest request) {
        Reservation r = inventory.reserve(request.security(), request.quantity());
        BorrowStatus status = r.reserved() ? BorrowStatus.BOOKED : BorrowStatus.PENDING_LOCATE;
        Borrow b = new Borrow(UUID.randomUUID().toString(), request.security(), request.quantity(), status);
        borrows.put(b.id(), b);
        return b;
    }
}
