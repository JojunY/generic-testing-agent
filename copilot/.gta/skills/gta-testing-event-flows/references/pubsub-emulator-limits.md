# Pub/Sub emulator limits

Supported locally: publish, pull/streaming pull, ack deadlines, dead-letter topics, message filtering, ordering keys.

Not supported: IAM, schema validation, single message transforms, BigQuery and Cloud Storage subscriptions, exactly-once delivery guarantees.

gta does not test deployed environments, so behaviour that depends on unsupported features is not a scenario: record it under `## Findings` as untested locally, for the team's SIT/UAT testing.
