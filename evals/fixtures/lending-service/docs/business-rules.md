# Lending business rules

- A borrow is only booked when inventory-service reserved the shares; while inventory-service cannot be reached, a booking stays pending a locate and the API answers 503.
- A recall never makes a borrower return more shares than were borrowed.
- The daily lending fee is quantity x price x annual rate / 360 (Act/360), rounded half-even to cents.
