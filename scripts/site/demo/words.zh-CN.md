# 录制中脚本化 agent 说的话（scripts/site/record-demo.sh --lang zh-CN）

- THEN_DRAFT: 响应状态是 `201`，借入状态是 `BOOKED`
- THEN_ANSWERED: 响应状态是 `503`，借入状态是 `PENDING_LOCATE`
- ACCEPT: 测试断言了响应状态 400
- REASON: inventory-service 没有应答时，InventoryClient#inventoryDown 报告股票已预留
