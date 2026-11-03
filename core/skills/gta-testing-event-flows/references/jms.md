# JMS / MQ specifics

- Transacted sessions: an exception in `@JmsListener` rolls back and the message is redelivered; after the redelivery limit it goes to the backout/DLQ queue — assert the queue it ends up in.
- Selectors: messages not matching stay on the queue.
- Persistent vs non-persistent delivery: assert the delivery mode the spec requires on the producer.
- Request/reply: assert `JMSCorrelationID` and the reply queue.
- Embedded Artemis proves JMS semantics; IBM MQ queue-manager configuration (channels, backout threshold set on the queue) is verified in P4.
