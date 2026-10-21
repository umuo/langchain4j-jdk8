#!/bin/sh
# Read-only API comparison of fixed-baseline source with an already built WIP JAR.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
df -h /Volumes/Nas
CANDIDATE="$TASK_ROOT/langchain4j-agentic-patterns/target/langchain4j-agentic-patterns-1.23.0-jdk8-SNAPSHOT.jar"
CLASSPATH_FILE="$TASK_ROOT/jdk8/example/target/classpath.txt"
test -f "$CANDIDATE" && test -f "$CLASSPATH_FILE"
mkdir -p "$TASK_ROOT/jdk8/target"
SCRATCH=$(mktemp -d "$TASK_ROOT/jdk8/target/patterns-api.XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT HUP INT TERM
python3 - "$TASK_ROOT" "$SCRATCH" "$JAVA17_HOME" "$CLASSPATH_FILE" <<'PY'
from pathlib import Path
import subprocess
import sys
import re
root, scratch, java, cp = map(Path, sys.argv[1:])
archive = subprocess.run(['git', '-C', str(root), 'archive',
    '7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79',
    'langchain4j-agentic-patterns/src/main/java'], capture_output=True, check=True)
subprocess.run(['tar', '-xf', '-', '-C', str(scratch)], input=archive.stdout, check=True)
classes = scratch / 'classes'
classes.mkdir()
pattern_cp = root / 'langchain4j-agentic/target/langchain4j-agentic-1.23.0-jdk8-SNAPSHOT.jar'
cp_value = str(pattern_cp) + ':' + cp.read_text().strip()
(scratch / 'classpath.txt').write_text(cp_value)
sources = sorted((scratch / 'langchain4j-agentic-patterns/src/main/java').rglob('*.java'))
assert len(sources) == 15, 'Pinned patterns source coverage changed'
subprocess.run([str(java / 'bin/javac'), '--release', '17', '-parameters', '-encoding', 'UTF-8',
    '-cp', cp_value, '-d', str(classes)] + [str(p) for p in sources], check=True)
# Compare actual annotation constants emitted by javac, including the trailing newline.
def prompt(artifact):
    data = subprocess.check_output([str(java / 'bin/javap'), '-v', '-classpath', str(artifact),
        'dev.langchain4j.agentic.patterns.p2p.VariablesExtractorAgent'], text=True)
    annotation = re.search(r'dev\.langchain4j\.service\.UserMessage\(\s*\n\s*(value=.*)\n\s*\)', data)
    assert annotation, 'Missing UserMessage annotation'
    return annotation.group(1)
assert prompt(classes) == prompt(root / 'langchain4j-agentic-patterns/target/langchain4j-agentic-patterns-1.23.0-jdk8-SNAPSHOT.jar')
print('Patterns extractor prompt annotation preserved byte-for-byte after text-block conversion.')
PY
"$JAVA17_HOME/bin/java" "$TASK_ROOT/jdk8/audit/AgenticBinaryApi.java" "$SCRATCH/classes" "$CANDIDATE" "$SCRATCH/classpath.txt" "Agentic patterns"
