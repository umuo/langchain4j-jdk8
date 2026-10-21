#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
: "${JDK8_REPOSITORY:=/Volumes/Nas/envs/maven/repository}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
"$JAVA17_HOME/bin/java" --class-path "$JDK8_REPOSITORY/com/github/javaparser/javaparser-core/3.28.2/javaparser-core-3.28.2.jar" "$TASK_ROOT/jdk8/audit/AgenticTestFixtureAudit.java" "$TASK_ROOT"
