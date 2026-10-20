# Migration tools

These JDK 17 source-launcher programs are one-time aids used to migrate the pinned upstream baseline. `InferTypes` attributes the unmodified source to infer `var` and anonymous diamond types; `LowerSyntax` rewrites selected syntax; `FixApis` and `RecordJson` handle follow-up adaptations. Final source also contains manually reviewed fixes.

They are **not idempotent**, do not constitute an automated upgrade pipeline, and must not be rerun on the current migrated source. Future upstream merges require normal review, compiler checks and acceptance tests. `tools/pom.xml` is an isolated tooling-only dependency resolver; it is never part of the production reactor or runtime classpath.
