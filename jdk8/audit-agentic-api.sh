#!/bin/sh
# Read-only API comparison of fixed-baseline source with an already built WIP JAR.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
df -h /Volumes/Nas
CANDIDATE="$TASK_ROOT/langchain4j-agentic/target/langchain4j-agentic-1.23.0-jdk8-SNAPSHOT.jar"
CLASSPATH_FILE="$TASK_ROOT/jdk8/example/target/classpath.txt"
test -f "$CANDIDATE" && test -f "$CLASSPATH_FILE"
mkdir -p "$TASK_ROOT/jdk8/target"
SCRATCH=$(mktemp -d "$TASK_ROOT/jdk8/target/agentic-api.XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT HUP INT TERM
python3 - "$TASK_ROOT" "$SCRATCH" "$JAVA17_HOME" "$CLASSPATH_FILE" <<'PY'
from pathlib import Path
import subprocess
import sys
root, scratch, java, cp = map(Path, sys.argv[1:])
archive = subprocess.run(['git', '-C', str(root), 'archive',
    '7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79',
    'langchain4j-agentic/src/main/java'], capture_output=True, check=True)
subprocess.run(['tar', '-xf', '-', '-C', str(scratch)], input=archive.stdout, check=True)
classes = scratch / 'classes'
classes.mkdir()
sources = sorted((scratch / 'langchain4j-agentic/src/main/java').rglob('*.java'))
assert len(sources) == 158, 'Pinned main Agentic source coverage changed'
subprocess.run([str(java / 'bin/javac'), '--release', '17', '-parameters', '-encoding', 'UTF-8',
    '-cp', cp.read_text().strip(), '-d', str(classes)] + [str(p) for p in sources], check=True)
PY
"$JAVA17_HOME/bin/java" "$TASK_ROOT/jdk8/audit/AgenticBinaryApi.java" "$SCRATCH/classes" "$CANDIDATE" "$CLASSPATH_FILE"
