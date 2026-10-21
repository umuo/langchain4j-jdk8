# Agentic 移植前审计（WIP）

固定基线 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79` 的四模块声明级快照；187 个生产 Java 文件全部成功解析并与 Git 树逐文件核对。
机器清单见 [AGENTIC-API-INVENTORY.json](AGENTIC-API-INVENTORY.json)，可用 `./jdk8/audit-agentic.sh` 从固定 Git 对象重建，不读取已改造源码作为基线。

**这是固定基线声明快照，不是当前移植状态。** 当前 WIP 改造与验证见 [AGENTIC-PORT.md](AGENTIC-PORT.md) 和 PROGRESS.md；全部 Agentic 尚未完成验收。record 隐式组件访问器/构造器由组件列表记录，不算入显式成员数量。

| 模块 | Java 文件 | 声明类型（含内部） | 显式候选成员 | 实现/功能验收 |
|---|---:|---:|---:|---|
| `langchain4j-agentic` | 158 | 194 | 1073 | pending |
| `langchain4j-agentic-a2a` | 8 | 10 | 60 | pending |
| `langchain4j-agentic-mcp` | 6 | 8 | 59 | pending |
| `langchain4j-agentic-patterns` | 15 | 22 | 100 | pending |

## 生产依赖声明

以下解析模块自身 POM 的属性和直接生产依赖；不是 effective POM 或传递依赖完整审计。测试与 Jackson 3 profile 不进入生产候选。

- `langchain4j-agentic`：`dev.langchain4j:langchain4j:1.23.0-SNAPSHOT`
- `langchain4j-agentic-a2a`：`org.a2aproject.sdk:a2a-java-sdk-client:1.4.0.Final`；`org.a2aproject.sdk:a2a-java-sdk-client-transport-jsonrpc:1.4.0.Final`；`dev.langchain4j:langchain4j-agentic:1.23.0-beta33-SNAPSHOT`
- `langchain4j-agentic-mcp`：`dev.langchain4j:langchain4j-agentic:1.23.0-beta33-SNAPSHOT`；`dev.langchain4j:langchain4j-mcp:1.23.0-beta33-SNAPSHOT`
- `langchain4j-agentic-patterns`：`dev.langchain4j:langchain4j-agentic:1.23.0-beta33-SNAPSHOT`

主模块仅直接依赖已回移植的 langchain4j；patterns 依赖主 Agentic，MCP 集成依赖主 Agentic 与 MCP。A2A 集成另有 SDK 1.4.0.Final 的两个模块及其传递依赖，目前没有 Java 8 产物证据，必须单独审计/移植或替代，不能因此排除 A2A。

## 语言与 API 候选

AST 精确语法节点与按方法名筛选的 API 候选分开理解：后者未做类型解析，可能是应用同名方法；每项有源文件和行号，不代表已证明不兼容或已修复。

| 候选 | 出现次数 |
|---|---:|
| `api-candidate:copyOf` | 7 |
| `api-candidate:formatted` | 1 |
| `api-candidate:isBlank` | 14 |
| `api-candidate:of` | 101 |
| `api-candidate:toList` | 42 |
| `api-candidate:writeString` | 5 |
| `instanceof-pattern` | 95 |
| `record` | 33 |
| `switch-expression` | 20 |
| `switch-rule` | 93 |
| `text-block` | 7 |
| `var` | 25 |

## 全部生产包

| 模块/包 | 文件数 |
|---|---:|
| `langchain4j-agentic` / `dev.langchain4j.agentic` | 3 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.agent` | 8 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.declarative` | 38 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.internal` | 31 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.observability` | 16 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.planner` | 14 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.scope` | 14 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.supervisor` | 10 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.workflow` | 10 |
| `langchain4j-agentic` / `dev.langchain4j.agentic.workflow.impl` | 14 |
| `langchain4j-agentic-a2a` / `dev.langchain4j.agentic.a2a` | 8 |
| `langchain4j-agentic-mcp` / `dev.langchain4j.agentic.mcp` | 6 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.bdi` | 2 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.blackboard` | 2 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.debate` | 2 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.decisionrouter` | 1 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.goap` | 3 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.p2p` | 3 |
| `langchain4j-agentic-patterns` / `dev.langchain4j.agentic.patterns.voting` | 2 |

## 完整功能验收清单（状态见 AGENTIC-PORT.md）

- 基础 Agent、typed/untyped/non-AI/action 构建器、声明式 AgenticServices 与注解代理、参数与返回值映射、配置/钩子。
- 顺序、条件、并行、parallel mapper、循环工作流；并行状态隔离、汇合、循环退出和失败传播。
- Human-in-the-loop、planner-based 与 supervisor 系统、路由/规划/响应策略，不能用简单顺序工作流替代。
- AgenticScope 与存储、会话/记忆、状态读写、挂起/恢复、工具调用和补偿、错误恢复、取消/关闭/线程生命周期。
- observability/monitor/listener/HTML 等公开能力；记录和序列化差异。
- patterns 模块所有实际模式（按文件清单逐项推进），以及 MCP schema/代理和 A2A typed/untyped、任务/租户/上下文/流式集成。
- 每个模块纳入 release 8 reactor/BOM、实际 Java 8、本地服务正常/错误/并发/取消/超时、独立消费与字节码/API/链接审计。
- Guardrails 专项、Spring Boot Starter 和 Jackson 3 继续排除；其余实际公开能力不因为依赖较新或测试困难而省略。

下一步先解析有效依赖并建立编译入口，再按主模块构建器/作用域/工作流推进；实现状态单独维护，审计清单不计完成，也不缩减 MCP 或模型/向量库的剩余目标。
