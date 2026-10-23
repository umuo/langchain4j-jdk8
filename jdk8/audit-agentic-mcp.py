#!/usr/bin/env python3
"""Audit actual production artifacts and their resolved runtime dependencies."""
import struct
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
classpath = root / 'jdk8/agentic-mcp-example/target/classpath.txt'
paths = {Path(p) for p in classpath.read_text().strip().split(':') if p}
expected = {'langchain4j-agentic', 'langchain4j-agentic-patterns', 'langchain4j-agentic-mcp'}
for name in expected:
    assert any(p.name == name + '-1.23.0-jdk8-SNAPSHOT.jar' for p in paths), name
errors = []
classes = 0
for path in sorted(paths):
    if not path.is_file() or path.suffix != '.jar':
        errors.append(f'Missing runtime JAR: {path}')
        continue
    if any(name in path.name for name in ['mutiny-zero', 'langchain4j-http-client-jdk']):
        errors.append(f'Forbidden runtime dependency: {path.name}')
    if '/dev/langchain4j/' in str(path) and '/dev/langchain4j/jdk8/' not in str(path):
        errors.append(f'Official artifact leaked into runtime: {path}')
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if not name.endswith('.class') or name.startswith('META-INF/versions/') or name == 'module-info.class':
                continue
            data = jar.read(name)
            classes += 1
            major = struct.unpack('>H', data[6:8])[0]
            if major > 52:
                errors.append(f'{path.name}: {name} requires classfile {major}')
            # Public/base class constant pools must not reference these missing Java 8 classes.
            for forbidden in [b'java/util/concurrent/Flow$', b'java/net/http/', b'java/lang/Record', b'mutiny/zero/']:
                if forbidden in data:
                    errors.append(f'{path.name}: {name} references {forbidden.decode()}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print(f'Experimental Agentic MCP Java 8 runtime audit passed: {len(paths)} JARs, {classes} base classes; multi-release entries and module-info excluded.')
