# gRPC (P3)

- Component: start the server in-process (`InProcessServerBuilder` / Spring gRPC in-process transport) and call it with a blocking stub.
- Assert status codes (`Status.Code.INVALID_ARGUMENT`, `NOT_FOUND`) and the response message fields named in THEN.
- Deadlines: set a client deadline and assert `DEADLINE_EXCEEDED` for timeout scenarios.
- Contracts: `.proto` compatibility is checked by the contract skill.
