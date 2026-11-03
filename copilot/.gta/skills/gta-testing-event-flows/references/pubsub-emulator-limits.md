# Pub/Sub emulator limits

Supported locally: publish, pull/streaming pull, ack deadlines, dead-letter topics, message filtering, ordering keys.

Not supported: IAM, schema validation, single message transforms, BigQuery and Cloud Storage subscriptions, exactly-once delivery guarantees.

Scenarios that depend on unsupported features are P4 system tests; note them in the spec with `hint.layer=system`.
