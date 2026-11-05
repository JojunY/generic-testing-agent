# gRPC (P3)

- Component: `@SpringBootTest` with `@AutoConfigureTestGrpcTransport` (Spring Boot 4, `org.springframework.boot.grpc.test.autoconfigure`) runs the server in-process; build a blocking stub from the injected `GrpcChannelFactory`:
  ```java
  StockServiceGrpc.newBlockingStub(channels.createChannel("catalog")).getStock(StockRequest.newBuilder().setProductId("p-1").build())
  ```
  Without Spring gRPC, use `InProcessServerBuilder` and `InProcessChannelBuilder` with the same name.
- Assert the response fields named in THEN, and the status codes of failures: `assertThatThrownBy(...).isInstanceOf(StatusRuntimeException.class)` and `Status.fromThrowable(e).getCode()` is `NOT_FOUND`, `INVALID_ARGUMENT`, ….
- The request message is the input; only the fields of the reply (or the status) are expected values.
- Deadlines: set a client deadline (`withDeadlineAfter`) and assert `DEADLINE_EXCEEDED` for timeout scenarios.
- Streaming: collect a server stream into a list and assert it in full; for client streams, assert the reply after `onCompleted()`.
- Contracts: `.proto` compatibility is checked by the contract skill.
