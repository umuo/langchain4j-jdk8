#!/bin/sh
# Check the API comparator's override normalization and negative cases.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA17_HOME:=$HOME/.sdkman/candidates/java/17.0.13-zulu}"
mount | /usr/bin/grep -q ' on /Volumes/Nas '
df -h /Volumes/Nas
mkdir -p "$TASK_ROOT/jdk8/target"
python3 - "$TASK_ROOT" "$JAVA17_HOME" <<'PY'
from pathlib import Path
import subprocess, sys, tempfile
root, java=map(Path,sys.argv[1:])
with tempfile.TemporaryDirectory(prefix='api-comparator-',dir=root/'jdk8/target') as tmp:
    scratch=Path(tmp); cp=scratch/'classpath.txt'; cp.write_text('')
    def compile_fixture(name, override=False, parameter='String', generic='String'):
        folder=scratch/name; folder.mkdir()
        (folder/'Base.java').write_text('import java.util.*; public interface Base {default List<'+generic+'> value(){return Collections.emptyList();}}')
        method='public java.util.List<String> value(){return java.util.Collections.emptyList();}' if override else ''
        (folder/'Leaf.java').write_text('public class Leaf implements Base {'+method+' public void token('+parameter+' value){}}')
        subprocess.run([str(java/'bin/javac'),'--release','17','-d',str(folder),str(folder/'Base.java'),str(folder/'Leaf.java')],check=True,capture_output=True)
        return folder
    before=compile_fixture('before')
    cases=[('override',compile_fixture('override',override=True),True),('parameter',compile_fixture('parameter',parameter='Integer'),False),('generic',compile_fixture('generic',generic='Integer'),False)]
    for name, after, compatible in cases:
        result=subprocess.run([str(java/'bin/java'),str(root/'jdk8/audit/AgenticBinaryApi.java'),str(before),str(after),str(cp),'comparator fixture'],capture_output=True,text=True)
        assert (result.returncode==0)==compatible, (name,result.stdout,result.stderr)
        if not compatible: assert 'Missing/changed API' in result.stderr
        if compatible: assert 'ADDED public java.util.List<java.lang.String> Leaf.value()' in result.stdout
print('API comparator validation passed: inherited default-to-class override accepted; changed parameter and changed generic return rejected.')
PY
