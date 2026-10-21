#!/usr/bin/env python3
"""Report a fixed-baseline declaration inventory. Does not claim effective/transitive dependency validation."""
import collections
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

repo, source = map(Path, sys.argv[1:])
path = repo / 'jdk8/AGENTIC-API-INVENTORY.json'
data = json.loads(path.read_text())
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
tracked = subprocess.check_output(['git', '-C', str(repo), 'ls-tree', '-r', '--name-only', data['baseline']], text=True).splitlines()
module_names = {module['module'] for module in data['modules']}
expected = {p for p in tracked if p.split('/')[0] in module_names and '/src/main/java/' in p and p.endswith('.java')}
actual = {file['path'] for module in data['modules'] for file in module['files']}
assert actual == expected, (expected - actual, actual - expected)
lines = ['# Agentic 移植前审计（WIP）', '',
         f"固定基线 `{data['baseline']}` 的四模块声明级快照；{len(actual)} 个生产 Java 文件全部成功解析并与 Git 树逐文件核对。",
         '机器清单见 [AGENTIC-API-INVENTORY.json](AGENTIC-API-INVENTORY.json)，可用 `./jdk8/audit-agentic.sh` 从固定 Git 对象重建，不读取已改造源码作为基线。', '',
         '**这是固定基线声明快照，不是当前移植状态。** 当前 WIP 改造与验证见 [AGENTIC-PORT.md](AGENTIC-PORT.md) 和 PROGRESS.md；全部 Agentic 尚未完成验收。record 隐式组件访问器/构造器由组件列表记录，不算入显式成员数量。', '',
         '| 模块 | Java 文件 | 声明类型（含内部） | 显式候选成员 | 实现/功能验收 |', '|---|---:|---:|---:|---|']
for module in data['modules']:
    files = module['files']
    types = sum(len(f['types']) for f in files)
    members = sum(len(t['members']) for f in files for t in f['types'])
    lines.append(f"| `{module['module']}` | {len(files)} | {types} | {members} | pending |")
lines += ['', '## 生产依赖声明', '', '以下解析模块自身 POM 的属性和直接生产依赖；不是 effective POM 或传递依赖完整审计。测试与 Jackson 3 profile 不进入生产候选。', '']
for module in data['modules']:
    root = ET.parse(source / module['module'] / 'pom.xml').getroot()
    props = {n.tag.split('}')[-1]: n.text for n in root.findall('m:properties/*', ns)}
    deps = []
    for dep in root.findall('m:dependencies/m:dependency', ns):
        value = {k: dep.findtext('m:' + k, namespaces=ns) for k in ('groupId', 'artifactId', 'version', 'scope')}
        if value['scope'] in ('test', 'provided'):
            continue
        declared = value['version']
        value['declaredVersion'] = declared
        if declared and declared.startswith('${') and declared.endswith('}'):
            value['version'] = props.get(declared[2:-1], declared)
        deps.append(value)
    module['direct_production_dependencies'] = deps
    lines.append(f"- `{module['module']}`：" + '；'.join(f"`{d['groupId']}:{d['artifactId']}:{d['version']}`" for d in deps))
lines += ['', '主模块仅直接依赖已回移植的 langchain4j；patterns 依赖主 Agentic，MCP 集成依赖主 Agentic 与 MCP。A2A 集成另有 SDK 1.4.0.Final 的两个模块及其传递依赖，目前没有 Java 8 产物证据，必须单独审计/移植或替代，不能因此排除 A2A。', '', '## 语言与 API 候选', '', 'AST 精确语法节点与按方法名筛选的 API 候选分开理解：后者未做类型解析，可能是应用同名方法；每项有源文件和行号，不代表已证明不兼容或已修复。', '', '| 候选 | 出现次数 |', '|---|---:|']
findings = collections.Counter(x['kind'] for m in data['modules'] for f in m['files'] for x in f['java8_findings'])
for kind, count in sorted(findings.items()):
    lines.append(f'| `{kind}` | {count} |')
lines += ['', '## 全部生产包', '', '| 模块/包 | 文件数 |', '|---|---:|']
for module in data['modules']:
    for package, count in sorted(collections.Counter(f['package'] for f in module['files']).items()):
        lines.append(f"| `{module['module']}` / `{package}` | {count} |")
lines += ['', '## 完整功能验收清单（状态见 AGENTIC-PORT.md）', '',
          '- 基础 Agent、typed/untyped/non-AI/action 构建器、声明式 AgenticServices 与注解代理、参数与返回值映射、配置/钩子。',
          '- 顺序、条件、并行、parallel mapper、循环工作流；并行状态隔离、汇合、循环退出和失败传播。',
          '- Human-in-the-loop、planner-based 与 supervisor 系统、路由/规划/响应策略，不能用简单顺序工作流替代。',
          '- AgenticScope 与存储、会话/记忆、状态读写、挂起/恢复、工具调用和补偿、错误恢复、取消/关闭/线程生命周期。',
          '- observability/monitor/listener/HTML 等公开能力；记录和序列化差异。',
          '- patterns 模块所有实际模式（按文件清单逐项推进），以及 MCP schema/代理和 A2A typed/untyped、任务/租户/上下文/流式集成。',
          '- 每个模块纳入 release 8 reactor/BOM、实际 Java 8、本地服务正常/错误/并发/取消/超时、独立消费与字节码/API/链接审计。',
          '- Guardrails 专项、Spring Boot Starter 和 Jackson 3 继续排除；其余实际公开能力不因为依赖较新或测试困难而省略。', '',
          '下一步先解析有效依赖并建立编译入口，再按主模块构建器/作用域/工作流推进；实现状态单独维护，审计清单不计完成，也不缩减 MCP 或模型/向量库的剩余目标。', '']
data['limitations'].append('Record implicit constructors/accessors are represented by components; explicit member count includes internal owners and is not a binary API count')
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
(repo / 'jdk8/AGENTIC-AUDIT.md').write_text('\n'.join(lines))
print('Baseline coverage verified:', len(actual), 'source files; all functional states pending')
