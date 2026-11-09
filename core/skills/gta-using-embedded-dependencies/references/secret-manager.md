# Secret Manager in tests

A service that imports secrets (`spring.config.import=sm@` and `${sm@<secret>}` placeholders, from
`spring-cloud-gcp-starter-secretmanager`) resolves them while Spring Boot loads its configuration, before any bean
exists. `spring.cloud.gcp.secretmanager.enabled=false` does not stop that, and gtaw runs tests without GCP credentials,
so the context fails with "Your default credentials were not found". Never give tests real credentials: register a fake
client in the bootstrap registry instead (checked on Spring Boot 4.1 with spring-cloud-gcp 8.3).

`src/test/java/<base>/support/FakeSecretManager.java`:

```java
public class FakeSecretManager implements BootstrapRegistryInitializer {
    static final Map<String, String> SECRETS = Map.of("inventory-api-key", "test-inventory-key");

    @Override
    public void initialize(BootstrapRegistry registry) {
        SecretManagerServiceClient client = mock(SecretManagerServiceClient.class);
        when(client.accessSecretVersion(any(SecretVersionName.class))).thenAnswer(call -> {
            String secret = call.<SecretVersionName>getArgument(0).getSecret();
            return AccessSecretVersionResponse.newBuilder()
                    .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(SECRETS.getOrDefault(secret, ""))))
                    .build();
        });
        GcpProjectIdProvider project = () -> "test-project";
        SecretManagerServiceClientFactory clients = location -> client;
        registry.register(SecretManagerServiceClientFactory.class, BootstrapRegistry.InstanceSupplier.of(clients));
        registry.register(SecretManagerServiceClient.class, BootstrapRegistry.InstanceSupplier.of(client));
        registry.register(GcpProjectIdProvider.class, BootstrapRegistry.InstanceSupplier.of(project));
    }
}
```

`src/test/resources/META-INF/spring.factories` (test classpath only, so production never sees it):

```properties
org.springframework.boot.bootstrap.BootstrapRegistryInitializer=com.example.lending.support.FakeSecretManager
```

- On Spring Boot 3.x the interfaces are `org.springframework.boot.BootstrapRegistryInitializer` and
  `org.springframework.boot.BootstrapRegistry`.
- Put the fake in the foundation item (the seed test proves the context starts with it); every test then gets the
  fake values. A scenario that depends on a secret's value asserts against `SECRETS`.
- Other GCP starters in the same service (Pub/Sub, Storage) are switched off for tests in
  `src/test/resources/config/application.properties`, which Spring Boot reads after the main `application.properties`
  without hiding it (`spring.cloud.gcp.pubsub.enabled=false`). Never put an `application.properties` directly in
  `src/test/resources`: it would replace the main one.
