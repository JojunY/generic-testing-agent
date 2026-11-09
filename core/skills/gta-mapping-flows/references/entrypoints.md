# Entry points and flow ids

gtaw builds the entry-point inventory deterministically (`.gta/work/flow-catalog.json`):

| Kind | Detected from | Capability |
|---|---|---|
| HTTP | `@RestController`/`@Controller` methods with `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`, `@RequestMapping` (`Mono`/`Flux` returns: `webflux`; event streams: `websocket`) | `rest-api` |
| RPC, GraphQL | `*ImplBase` overrides; `@QueryMapping`, `@MutationMapping`, `@SubscriptionMapping` | `grpc-api`, `graphql-api` |
| Schedule | `@Scheduled` methods, Quartz jobs | `scheduler` |
| Listener | `@KafkaListener`, `@JmsListener`, `@ServiceActivator`, `@RabbitListener`, `@SqsListener`, application event listeners | `messaging` |
| Function | `@Bean Function`/`Consumer`/`Supplier`: bound by Spring Cloud Stream (`messaging`), or a Spring Cloud Function such as a Cloud Run function (`cloud-function`) | `messaging`, `cloud-function` |
| Integration flow | `@Bean IntegrationFlow` | `integration` |
| Job, pipeline, workflow | `@Bean Job`; composite `PTransform`; Temporal workflow methods, state machines, `*.bpmn` | `spring-batch`, `beam-pipeline`, `workflow` |
| Migration | `db/migration/V*__*.sql`, `db/changelog/*` | `db-migration` |

Resilience4j, Spring Retry, Secret Manager, security, caches and Cucumber never make flows of their own: they add their
skill to the flows of the service.

## Flow id

`<domain>.<flow-name>`, exactly two segments, lowercase kebab-case.

- domain = class name without its role suffix (`Controller`, `Resource`, `Endpoint`, `Api`, `Listener`, `Consumer`, `Job`, `Scheduler`, `Task`, `Handler`, `Functions`, `IntegrationConfig`, `Config`, …)
- flow-name = method name in kebab-case

`BorrowController#book` → `borrow.book`; `RecallFunctions#recall` → `recall.recall`; `FeeAccrualIntegrationConfig#feeAccrualFlow` → `fee-accrual.flow`; `PositionImportJob#runNightly` → `position-import.run-nightly`.
`foundation` is reserved.

## Custom frameworks

If the service has entry points gtaw cannot see (an in-house dispatcher, reflection), say so in the spec's Boundary and reference the dispatcher method as the entry `ref`.
