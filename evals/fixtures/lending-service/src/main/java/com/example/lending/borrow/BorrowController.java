package com.example.lending.borrow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/borrows")
public class BorrowController {
    private final BorrowService service;

    public BorrowController(BorrowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Borrow> book(@RequestBody BorrowRequest request) {
        if (request.quantity() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        Borrow b = service.book(request);
        return ResponseEntity.status(b.status() == BorrowStatus.BOOKED ? HttpStatus.CREATED : HttpStatus.SERVICE_UNAVAILABLE)
                .body(b);
    }
}
