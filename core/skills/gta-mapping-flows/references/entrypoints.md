# Entry points and flow ids

gtaw builds the entry-point inventory deterministically (`.gta/work/flow-catalog.json`):

| Kind | Detected from | Capability |
|---|---|---|
| HTTP | `@RestController`/`@Controller` methods with `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`, `@RequestMapping` | `rest-api` |
| Schedule | `@Scheduled` methods | `scheduler` (P2) |
| Listener | `@KafkaListener`, `@JmsListener`, `@ServiceActivator` | `messaging` (P3) |

## Flow id

`<domain>.<flow-name>`, exactly two segments, lowercase kebab-case.

- domain = class name without `Controller`, `Resource`, `Endpoint`, `Api`, `Listener`, `Consumer`, `Job`, `Scheduler`, `Task`, `Handler`
- flow-name = method name in kebab-case

`OrderController#create` → `order.create`; `InvoiceImportJob#runNightly` → `invoice-import.run-nightly`.
`foundation` is reserved.

## Custom frameworks

If the service has entry points gtaw cannot see (an in-house dispatcher, reflection), say so in the spec's Boundary and reference the dispatcher method as the entry `ref`.
