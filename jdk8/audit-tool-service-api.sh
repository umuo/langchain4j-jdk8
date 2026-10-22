#!/bin/sh
# Compare the affected tool service with the pinned upstream declaration, without relaxing API rules.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
df -h /Volumes/Nas
CANDIDATE="$TASK_ROOT/langchain4j/target/langchain4j-1.23.0-jdk8-SNAPSHOT.jar"
CLASSPATH_FILE="$TASK_ROOT/jdk8/example/target/classpath.txt"
test -f "$CANDIDATE" && test -f "$CLASSPATH_FILE"
mkdir -p "$TASK_ROOT/jdk8/target"
SCRATCH=$(mktemp -d "$TASK_ROOT/jdk8/target/tool-service-api.XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT HUP INT TERM
mkdir -p "$SCRATCH/src/dev/langchain4j/service/tool" "$SCRATCH/classes"
git -C "$TASK_ROOT" show '7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79:langchain4j/src/main/java/dev/langchain4j/service/tool/ToolService.java' > "$SCRATCH/src/dev/langchain4j/service/tool/ToolService.java"
"$JAVA17_HOME/bin/javac" --release 17 -parameters -encoding UTF-8 -cp "$(cat "$CLASSPATH_FILE")" -d "$SCRATCH/classes" "$SCRATCH/src/dev/langchain4j/service/tool/ToolService.java"
"$JAVA17_HOME/bin/java" "$TASK_ROOT/jdk8/audit/AgenticBinaryApi.java" "$SCRATCH/classes" "$CANDIDATE" "$CLASSPATH_FILE" 'main ToolService'
