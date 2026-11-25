# What the scripted agents say in the recording (scripts/site/record-demo.sh)

- THEN_DRAFT: the response status is `201` and the borrow status is `BOOKED`
- THEN_ANSWERED: the response status is `503` and the borrow status is `PENDING_LOCATE`
- ACCEPT: the test asserts the status 400 on the response
- REASON: InventoryClient#inventoryDown reports the shares as reserved when inventory-service did not answer
