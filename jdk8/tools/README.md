# Migration tools

These JDK 17 source-launcher programs are one-time aids used to migrate the pinned upstream baseline. `InferTypes` attributes the unmodified source to infer `var` and anonymous diamond types; `LowerSyntax` rewrites selected syntax; `FixApis` and `RecordJson` handle follow-up adaptations. Final source also contains manually reviewed fixes.

They are **not idempotent**, do not constitute an automated upgrade pipeline, and must not be rerun on the current migrated source. Future upstream merges require normal review, compiler checks and acceptance tests. `tools/pom.xml` is an isolated tooling-only dependency resolver; it is never part of the production reactor or runtime classpath.

Stage two uses separate programs under `stage2/`, with attribution performed on pristine `git archive` copies of the pinned core and main module under `jdk8/target/stage2`. Only the new main module is lowered and copied into the working source; milestone-one modules are never fed back through the migration passes. Additional hand fixes, runtime tests and `Stage2RecordMetadata` complete that migration. The scratch directory is disposable and is removed by the normal clean build.
