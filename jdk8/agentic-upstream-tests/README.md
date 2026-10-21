# Agentic upstream regression harness (JDK 17, WIP)

The `agentic-wip` profile selects all **51** `*Test.java` source files present in the pinned main Agentic module at `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`. They are compiled directly from their original paths with unchanged assertions. This is a separate JDK 17 suite, not Java 8 acceptance, and does not include the model-service IT suite or assert complete Agentic support.

Run after the compatibility build prerequisites are available:

```sh
./jdk8/build.sh -o -Pagentic-wip
./jdk8/audit-agentic-tests.sh
```

The fixture audit verifies that all 51 selected baseline sources are byte-for-byte unchanged and none were omitted. It also checks these two fixture adaptations:

- `StreamingChatModelMock.java` is copied from the pinned core test fixture. Only the `Publisher` and `Subscription` imports change from `java.util.concurrent.Flow` to `org.reactivestreams`, matching the production signature change. Its behavior is otherwise identical.
- `SupervisorAgentIT.java` contains exactly the original `Document`, `Invoice` and `InvoiceRegistrationAgent` nested declarations, checked by AST equality against the fixed Git object. `InvoiceFixtureTest` refers to these declarations and their annotations. Extracting them avoids loading the IT's model configuration; no IT test method, model initializer or credential environment access is included.

The original ChatModelMock, JSON scope store, domain types, report sample and supplier fixtures are included explicitly. JUnit Jupiter/Mockito/AssertJ and JDK 17 test helpers stay in this harness; they are not production dependencies or part of the compatibility BOM.
