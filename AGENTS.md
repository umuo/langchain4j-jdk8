# LangChain4j Java 8 compatibility engineering

- Start by reading `jdk8/PROGRESS.md`, `jdk8/COMPATIBILITY.md`, and `git status`.
- Upstream baseline: `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`. This independent repository uses `jdk8/main`; do not edit the adjacent original repository.
- Build with `./jdk8/build.sh`. The original root POM is preserved for upstream comparison, not a supported compatibility build entry.
- Keep Java package names. Compatibility Maven artifacts use `dev.langchain4j.jdk8` and `1.23.0-jdk8-SNAPSHOT`.
- Production must compile with `--release 8`; acceptance must run on an actual Java 8 JVM. JDK 17 upstream regression tests are a separate, explicitly selected suite.
- Maintain API differences, validation results and next steps in the progress documents. Commit each reviewable increment; label incomplete checkpoints WIP. Do not push or publish unless asked.
- Keep caches and build data on the mounted `/Volumes/Nas`; Maven settings and repository are under `/Volumes/Nas/envs/maven`. Do not download large files to the system disk or touch required host services.
- Migration tools in `jdk8/tools` are one-time aids, not idempotent code generation. Never rerun them against edited sources as an upgrade strategy.
