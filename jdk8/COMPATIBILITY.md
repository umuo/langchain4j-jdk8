# Compatibility contract

- Java 8 production bytecode and APIs; Java 17 build tooling.
- Flow API becomes org.reactivestreams; callbacks remain available.
- Default HTTP implementation: OkHttp; JDK HTTP client excluded.
- Record classes become ordinary final value classes.
- Jackson 2 only. No Spring Boot starter in milestone one.
- No claim of compatibility until Java 8 acceptance passes.
