#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
: "${JAVA8_HOME:=$HOME/.sdkman/candidates/java/8.0.462-zulu}"
: "${JDK8_MAVEN:=/Volumes/Nas/envs/maven/current/bin/mvn}"
: "${JDK8_SETTINGS:=/Volumes/Nas/envs/maven/settings.xml}"
: "${JDK8_REPOSITORY:=/Volumes/Nas/envs/maven/repository}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
df -h /Volumes/Nas
mkdir -p "$TASK_ROOT/jdk8/native-tmp"
for runtime in "$JAVA17_HOME" "$JAVA8_HOME"; do
    test -x "$runtime/bin/java" || { echo "Missing JDK: $runtime; set JAVA17_HOME/JAVA8_HOME." >&2; exit 1; }
done
export JAVA_HOME="$JAVA17_HOME"
# Maven runs on 17; the acceptance module forks the actual Java 8 executable.
"$JDK8_MAVEN" -B -s "$JDK8_SETTINGS" -Dmaven.repo.local="$JDK8_REPOSITORY" -f "$TASK_ROOT/jdk8/pom.xml" -Pupstream-tests -Djava8.home="$JAVA8_HOME" clean install "$@"
"$JDK8_MAVEN" -B -s "$JDK8_SETTINGS" -Dmaven.repo.local="$JDK8_REPOSITORY" -f "$TASK_ROOT/jdk8/example/pom.xml" clean package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/classpath.txt
python3 "$TASK_ROOT/jdk8/audit.py"
"$JAVA8_HOME/bin/java" -Dorg.sqlite.tmpdir="$TASK_ROOT/jdk8/native-tmp" -Xverify:all -cp "$TASK_ROOT/jdk8/example/target/classes:$(cat "$TASK_ROOT/jdk8/example/target/classpath.txt")" RuntimeLinkage
"$JAVA8_HOME/bin/java" -Dorg.sqlite.tmpdir="$TASK_ROOT/jdk8/native-tmp" -Xverify:all -cp "$TASK_ROOT/jdk8/example/target/classes:$(cat "$TASK_ROOT/jdk8/example/target/classpath.txt")" Example
"$JAVA8_HOME/bin/java" -Dorg.sqlite.tmpdir="$TASK_ROOT/jdk8/native-tmp" -Xverify:all -cp "$TASK_ROOT/jdk8/example/target/classes:$(cat "$TASK_ROOT/jdk8/example/target/classpath.txt")" RagExample
"$JAVA8_HOME/bin/java" -Dorg.sqlite.tmpdir="$TASK_ROOT/jdk8/native-tmp" -Xverify:all -cp "$TASK_ROOT/jdk8/example/target/classes:$(cat "$TASK_ROOT/jdk8/example/target/classpath.txt")" HttpRagExample
# Two actual Java 8 processes prove restart persistence; the first only ingests, the second only queries.
"$JAVA8_HOME/bin/java" -Dorg.sqlite.tmpdir="$TASK_ROOT/jdk8/native-tmp" -Xverify:all -cp "$TASK_ROOT/jdk8/example/target/classes:$(cat "$TASK_ROOT/jdk8/example/target/classpath.txt")" HttpRagExample write "$TASK_ROOT/jdk8/example/target/restart-vectors.db"
"$JAVA8_HOME/bin/java" -Dorg.sqlite.tmpdir="$TASK_ROOT/jdk8/native-tmp" -Xverify:all -cp "$TASK_ROOT/jdk8/example/target/classes:$(cat "$TASK_ROOT/jdk8/example/target/classpath.txt")" HttpRagExample read "$TASK_ROOT/jdk8/example/target/restart-vectors.db"
