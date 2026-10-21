#!/bin/sh
# Read-only provenance for the complete pinned patterns unit-test selection.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 - "$TASK_ROOT" <<'PY'
from pathlib import Path
import subprocess, sys, xml.etree.ElementTree as E
root=Path(sys.argv[1]); baseline='7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79'
files=subprocess.check_output(['git','-C',str(root),'ls-tree','-r','--name-only',baseline,'langchain4j-agentic-patterns/src/test/java'],text=True).splitlines()
tests={f for f in files if f.endswith('Test.java')}
assert len(tests)==8
helper='langchain4j-core/src/test/java/dev/langchain4j/model/decision/mock/DecisionModelMock.java'
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=E.parse(root/'jdk8/patterns-upstream-tests/pom.xml')
selected={e.text for e in pom.findall('.//m:testInclude',ns)}
assert selected==tests|{helper}, (selected,tests)
for path in selected:
    original=subprocess.check_output(['git','-C',str(root),'show',baseline+':'+path])
    assert (root/path).read_bytes()==original, 'Modified original source: '+path
print('Patterns upstream fixture audit passed: all 8 baseline Test sources selected, 9 original test/helper sources unchanged; no Models or external-service IT configuration included.')
PY
