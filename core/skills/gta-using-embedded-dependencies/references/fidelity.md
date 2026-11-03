# Fidelity levels

| Level | Meaning | Examples |
|---|---|---|
| high | real implementation of the same product | Oracle shared schema, embedded Kafka, WireMock for HTTP |
| medium | faithful emulator of the protocol, some features missing | Pub/Sub emulator, Artemis for JMS semantics, in-memory GCS |
| low | different engine or a fake | H2 for Oracle, BigQuery fakes, hand-written fakes |

The gate raises a concern when a p0/p1 scenario only has low-fidelity tests. Say which level you used and why in your one-line result.
