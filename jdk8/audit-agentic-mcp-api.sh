#!/bin/sh
# Read-only API comparison of fixed-baseline source with an already built WIP JAR.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
if ! mount | /usr/bin/grep -q ' on /Volumes/Nas '; then
    echo 'External disk /Volumes/Nas is not mounted; stopping.' >&2; exit 1
fi
df -h /Volumes/Nas
CANDIDATE="$TASK_ROOT/langchain4j-agentic-mcp/target/langchain4j-agentic-mcp-1.23.0-jdk8-SNAPSHOT.jar"
CLASSPATH_FILE="$TASK_ROOT/jdk8/example/target/classpath.txt"
test -f "$CANDIDATE" && test -f "$CLASSPATH_FILE"
mkdir -p "$TASK_ROOT/jdk8/target"
SCRATCH=$(mktemp -d "$TASK_ROOT/jdk8/target/agentic-mcp-api.XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT HUP INT TERM
python3 - "$TASK_ROOT" "$SCRATCH" "$JAVA17_HOME" "$CLASSPATH_FILE" <<'PY'
from pathlib import Path
import subprocess
import sys
import re
root, scratch, java, cp = map(Path, sys.argv[1:])
archive = subprocess.run(['git', '-C', str(root), 'archive',
    '7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79',
    'langchain4j-agentic-mcp/src/main/java'], capture_output=True, check=True)
subprocess.run(['tar', '-xf', '-', '-C', str(scratch)], input=archive.stdout, check=True)
classes = scratch / 'classes'
classes.mkdir()
pattern_cp = root / 'langchain4j-agentic/target/langchain4j-agentic-1.23.0-jdk8-SNAPSHOT.jar'
cp_value = str(pattern_cp) + ':' + cp.read_text().strip()
(scratch / 'classpath.txt').write_text(cp_value)
sources = sorted((scratch / 'langchain4j-agentic-mcp/src/main/java').rglob('*.java'))
assert len(sources) == 6, 'Pinned Agentic MCP source coverage changed'
subprocess.run([str(java / 'bin/javac'), '--release', '17', '-parameters', '-encoding', 'UTF-8',
    '-cp', cp_value, '-d', str(classes)] + [str(p) for p in sources], check=True)
# All fixed-baseline non-IT tests must execute unchanged in the dedicated Java17 suite.
import xml.etree.ElementTree as ET
selected = {e.text.strip() for e in ET.parse(root / 'jdk8/agentic-mcp-upstream-tests/pom.xml').getroot().iter()
            if e.tag.rsplit('}',1)[-1] == 'testInclude'}
listing = subprocess.check_output(['git','-C',str(root),'ls-tree','-r','--name-only',
    '7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79','langchain4j-agentic-mcp/src/test/java'], text=True)
expected = {p for p in listing.splitlines() if p.endswith('Test.java')}
assert len(expected) == 2 and selected == expected
for name in expected:
    original = subprocess.check_output(['git','-C',str(root),'show','7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79:'+name])
    assert original == (root / name).read_bytes(), name
spi = 'langchain4j-agentic-mcp/src/main/resources/META-INF/services/dev.langchain4j.agentic.internal.McpService'
assert subprocess.check_output(['git','-C',str(root),'show','7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79:'+spi]) == (root / spi).read_bytes()
print('Agentic MCP fixture audit passed: both fixed-baseline Test sources unchanged/selected; SPI descriptor preserved.')

PY
"$JAVA17_HOME/bin/java" "$TASK_ROOT/jdk8/audit/AgenticBinaryApi.java" "$SCRATCH/classes" "$CANDIDATE" "$SCRATCH/classpath.txt" "Agentic MCP"
