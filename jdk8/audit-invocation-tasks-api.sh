#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
# This compatibility helper does not exist in the official baseline.
# Compare its established ABI with the preceding compatibility checkpoint instead.
BASELINE=6385ff008132aac0685b63f9f6f05d28efc3d6d4
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
df -h /Volumes/Nas
CANDIDATE="$TASK_ROOT/langchain4j-agentic/target/langchain4j-agentic-1.23.0-jdk8-SNAPSHOT.jar"
CLASSPATH_FILE="$TASK_ROOT/jdk8/example/target/classpath.txt"
test -f "$CANDIDATE" && test -f "$CLASSPATH_FILE"
mkdir -p "$TASK_ROOT/jdk8/target"
SCRATCH=$(mktemp -d "$TASK_ROOT/jdk8/target/invocation-tasks-api.XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT HUP INT TERM
python3 - "$TASK_ROOT" "$SCRATCH" "$JAVA17_HOME" "$CLASSPATH_FILE" "$BASELINE" "$CANDIDATE" <<'PY'
from pathlib import Path
import subprocess
import sys
root, scratch, java, cp = map(Path, sys.argv[1:5])
baseline, candidate = sys.argv[5:7]
source = scratch / 'InvocationTasks.java'
source.write_bytes(subprocess.check_output(['git', '-C', str(root), 'show',
    baseline + ':langchain4j-agentic/src/main/java/dev/langchain4j/agentic/internal/InvocationTasks.java']))
classpath = scratch / 'classpath.txt'
classpath.write_text(cp.read_text().strip() + ':' + candidate)
classes = scratch / 'classes'
classes.mkdir()
subprocess.run([str(java / 'bin/javac'), '--release', '8', '-parameters', '-encoding', 'UTF-8',
    '-cp', classpath.read_text(), '-d', str(classes), str(source)], check=True)
print('InvocationTasks compatibility API baseline: ' + baseline, flush=True)
subprocess.run([str(java / 'bin/java'), str(root / 'jdk8/audit/AgenticBinaryApi.java'),
    str(classes), candidate, str(classpath)], check=True)
PY
