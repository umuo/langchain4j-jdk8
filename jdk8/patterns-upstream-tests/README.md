# Agentic patterns upstream regressions (WIP)

This JDK 17 harness selects all eight `*Test.java` sources from the pinned patterns baseline, with their original assertions, plus the original core `DecisionModelMock` helper. It excludes external-service IT and `Models` configuration. Production uses the Java 8 candidate. These regressions are separate from the actual Java 8 acceptance in the patterns module and do not establish complete patterns support.

Selection and content are checked against Git baseline `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`. No original test sources are rewritten.
