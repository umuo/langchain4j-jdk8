#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
: "${JAVA8_HOME:=$HOME/.sdkman/candidates/java/8.0.462-zulu}"
: "${JDK8_MAVEN:=/Volumes/Nas/envs/maven/current/bin/mvn}"
: "${JDK8_SETTINGS:=/Volumes/Nas/envs/maven/settings.xml}"
: "${JDK8_REPOSITORY:=/Volumes/Nas/envs/maven/repository}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then echo 'External disk not mounted; stopping.' >&2; exit 1; fi
df -h /Volumes/Nas
export JAVA_HOME="$JAVA17_HOME"
"$JDK8_MAVEN" -B -s "$JDK8_SETTINGS" -Dmaven.repo.local="$JDK8_REPOSITORY" -f "$TASK_ROOT/jdk8/agentic-mcp-example/pom.xml" clean package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/classpath.txt "$@"
python3 "$TASK_ROOT/jdk8/audit-agentic-mcp.py"
"$JAVA17_HOME/bin/javac" --release 8 -d "$TASK_ROOT/jdk8/agentic-mcp-example/target/classes" "$TASK_ROOT/jdk8/example/src/main/java/RuntimeLinkage.java"
"$JAVA8_HOME/bin/java" -Xverify:all -cp "$TASK_ROOT/jdk8/agentic-mcp-example/target/classes:$(cat "$TASK_ROOT/jdk8/agentic-mcp-example/target/classpath.txt")" RuntimeLinkage
"$JAVA8_HOME/bin/java" -Xverify:all -cp "$TASK_ROOT/jdk8/agentic-mcp-example/target/classes:$(cat "$TASK_ROOT/jdk8/agentic-mcp-example/target/classpath.txt")" example.AgenticMcpExample
"$TASK_ROOT/jdk8/audit-agentic-mcp-api.sh"
