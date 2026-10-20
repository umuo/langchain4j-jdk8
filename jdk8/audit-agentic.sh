#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then echo 'External disk is not mounted' >&2; exit 1; fi
df -h /Volumes/Nas
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
: "${JDK8_REPOSITORY:=/Volumes/Nas/envs/maven/repository}"
AUDIT_ROOT="$TASK_ROOT/jdk8/target/agentic-baseline"
mkdir -p "$AUDIT_ROOT"
git -C "$TASK_ROOT" archive 7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79 langchain4j-agentic langchain4j-agentic-a2a langchain4j-agentic-mcp langchain4j-agentic-patterns | tar -x -C "$AUDIT_ROOT"
AUDIT_CP="$JDK8_REPOSITORY/com/github/javaparser/javaparser-core/3.28.2/javaparser-core-3.28.2.jar:$JDK8_REPOSITORY/com/fasterxml/jackson/core/jackson-databind/2.18.6/jackson-databind-2.18.6.jar:$JDK8_REPOSITORY/com/fasterxml/jackson/core/jackson-core/2.18.6/jackson-core-2.18.6.jar:$JDK8_REPOSITORY/com/fasterxml/jackson/core/jackson-annotations/2.18.6/jackson-annotations-2.18.6.jar"
"$JAVA17_HOME/bin/java" --class-path "$AUDIT_CP" "$TASK_ROOT/jdk8/audit/AgenticApiInventory.java" "$AUDIT_ROOT" "$TASK_ROOT/jdk8/AGENTIC-API-INVENTORY.json"
python3 "$TASK_ROOT/jdk8/audit/agentic_report.py" "$TASK_ROOT" "$AUDIT_ROOT"
