# Business rules

- Shipping: only orders in status PAID are shipped. CREATED orders wait for their payment.
- Loyalty: a customer earns one point per full 10.00 of the order total.
- Admin: only users with role ADMIN may call the admin API.
- Prices: a price change is visible on the next read.
- Archive: only SHIPPED orders are archived; CANCELLED orders stay for the refund team.
- Tiers: a customer with at least 100 points is GOLD.
