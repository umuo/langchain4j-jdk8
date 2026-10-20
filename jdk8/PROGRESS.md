# Java 8 移植进度

## 当前状态：第八阶段 MCP 基础客户端与工具桥接已完成（2026-10-20）

- 仓库：`/Volumes/Nas/codex-work/langchain4j-jdk8`；分支 `jdk8/main`；当前完成标记 `jdk8-m8`；历史标记 `jdk8-m1` 至 `jdk8-m7`。
- 固定上游基线：`7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。相邻原始仓库未修改；完整 Git 历史独立保存，不共享对象文件。
- Maven 坐标：`dev.langchain4j.jdk8:*:1.23.0-jdk8-SNAPSHOT`，兼容 BOM 现覆盖八个生产模块。已安装到外置盘 Maven 缓存；未远程推送或公共发布。

## 已完成能力

1. 首期五模块：core、HTTP 抽象、OkHttp、reactive-streaming、OpenAI 的 Java 8 源码与 Chat Completions 闭环。
2. 第二阶段加入 `langchain4j` 主模块：AI Services 接口代理、继承/非 public 接口的默认方法、可变参数/空数组/null、原始异常、注解提示词和可替换元数据 SPI。
3. 结构化 POJO 输出、Future、TokenStream 和 Reactive Streams Publisher；同步/异步会话隔离，以及 ChatMemoryAccess 查询与清理。
4. 自动工具循环：同步、Future、回调 SSE、响应式 SSE 四条路径，真实 loopback HTTP 联动，参数/结果回传、并发工具、失败和轮次限制。
5. Future/Publisher 取消后 HTTP 调用释放；内部取消专用回调与正常完成/失败区分。Java 17 下仍可反射读取 record 元数据。
6. 基础 RAG 全链路：加载、多文档分段/index/元数据、内存向量检索及过滤、JSON/文件持久化恢复、AI Services 上下文注入与来源；同步、Future、回调/响应式流式和 loopback HTTP/SSE。
7. 修复 RAG embedding 投影及 AI Services memory/augmentation 的取消链；验证检索失败、显式 offload 和保留问题原文的记忆配置。
8. OpenAI Embeddings HTTP：批量和顺序、model/token usage、参数/维度/Base64/listener、headers/query、错误/重试/超时与取消；HTTP embedding 接入四条 RAG 路径。
9. 修复 embedding 响应 index/数量/向量校验与底层 HTTP 独立取消后一直等待的问题；独立 BOM 的 HTTP RAG 示例在 Java 8 通过。
10. 独立 Java 8 官方 Publisher TCK（同线程/异步），取消/终止后 subscriber 引用释放；并发信号、终止竞争、GC、真实 SSE 溢出和取消释放。
11. 原始 AI Services 事件流在等待 RAG 时立即处理非法需求量；onSubscribe 立即取消不再启动检索，prologue/流式终止去重并释放 subscription 引用。
12. 新增 SQLite 文件向量存储：事务批量 upsert、类型保真元数据、精确余弦检索/过滤/删除、维度约束、重开恢复、锁/并发/连接生命周期；显式 offload 的四路径 RAG。
13. 独立 BOM 通过两个真实 Java 8 进程验证 HTTP embedding 入库退出，再重开 SQLite 完成异步 RAG，无重新入库。
14. SQLite 会话记忆：类型隔离 ID、核心消息/工具结果 JSON、整段事务更新/删除、同步和显式 Executor offload，与向量/RAG/四路径 AI Services 共存。
15. 修复窗口记忆和 AI Services 记忆组装的取消链；独立 JVM 恢复含工具调用/结果的历史，writer 4 条、reader 发 5 条并保存 6 条。

16. MCP 2025-11-25 工具子集：OkHttp HTTP JSON/连续 POST SSE、初始化/会话、工具分页/schema/调用、四条 AI Services 工具循环，以及直接取消/超时/close/并发和独立 BOM 消费。完整范围见 `../langchain4j-mcp/README-jdk8.md`。

## 最终验证

执行 `./jdk8/build.sh -o`（reactor 离线使用外置盘已缓存依赖），完整 clean install、独立 BOM 消费项目、字节码审计、真实 Java 8 类链接、AI Services 模拟对话、独立 RAG、HTTP embedding RAG、SQLite 跨进程和 MCP 工具示例全部通过。

| 验证 | 第八阶段结果 |
|---|---|
| 八个模块生产源码 | JDK 17 `--release 8` 构建通过 |
| 真实 Java 8 验收 | 173 个用例，0 失败/错误/跳过；JDK 1.8.0_462 |
| 选定上游及现代反射回归 | 850 个用例，0 失败/错误/跳过；JDK 17.0.13 |
| 官方 Publisher TCK / Java 8 | 76 项：62 通过、14 官方 untested 跳过；0 失败/错误，无 required/optional 跳过 |
| 字节码和禁止引用审计 | 33 个 JAR 文件、6278 个基础类通过；含项目构建/安装副本 |
| Java 8 类及成员类型加载 | 4902 个类通过；3 个可选 Android/Conscrypt/OSGi 入口排除 |
| 独立 BOM 消费示例 | Java 8 通过 AI Services 返回 `Java 8 OK` |
| 独立 BOM RAG 示例 | Java 8 返回 `7 yuan` 和对应 apple 文档来源 |
| 独立 BOM HTTP RAG 示例 | Java 8 返回 `7 yuan`；入库和查询共 2 次 embedding HTTP 请求 |
| SQLite 跨进程消费 | writer 2 段落持久化；独立 Java 8 reader 查询并返回 `7 yuan`，仅 1 次查询 embedding |
| SQLite 记忆跨进程消费 | writer 持久化 4 条含工具消息，reader 恢复到模型请求 5 条并保存 6 条 |
| 独立 BOM MCP 工具消费 | Java 8 OpenAI HTTP -> AI Services -> MCP HTTP 工具 -> 模型返回 `7 yuan`，工具调用 1 次 |
| SQLite native 引擎 | Xerial 3.53.4.0 / SQLite 3.53.4，Java 8/macOS/aarch64 通过 |
| 模型资源检查 | 兼容 JAR 只包含配套 1.9.3 模型，未打入 2.5.0 模型 |
| diff/shell 检查 | `git diff --check`、`sh -n jdk8/build.sh` 通过 |

结果快照：`verification/milestone-8.json`；历史快照保留为 `verification/milestone-7.json`（149 / 692 / 62 TCK）、 `verification/milestone-6.json`（122 / 627 / 62 TCK）、 `verification/milestone-5.json`（99 / 627 / 62 TCK）、 `verification/milestone-4.json`（89 / 627）、 `verification/milestone-3.json`（70 / 554）、 `verification/milestone-2.json`（51 个 Java 8 / 435 个上游用例）和 `verification/milestone-1.json`（25 / 228）。当前报告位于 `acceptance`、`upstream-tests`、`reactive-tck` 三个模块的 `target/surefire-reports`。这些是选定回归，不代表上游全量/Kotlin 测试已运行。

本阶段新增 24 个真实 Java 8 MCP 用例和 23 个上游 MCP 测试类（158 项），保留此前所有验收。总计 1085 项通过，另列 14 项官方 untested；没有失败/错误或其他跳过。Guardrails 继续跳过，未连接外部 MCP 服务器。

## 已知边界

- Java 8 的 Flow 签名替换、record/sealed 降级和线程模型差异继续适用；具体约定见 `COMPATIBILITY.md`。
- OpenNLP 2.x 不能运行于 Java 8。使用 1.9.5 和官方 0.35.0 历史中的 1.9.3 句子模型，未修改模型版本绕过校验；Java 8 和 6 个原始分割用例通过。1.9.x 已结束维护，模型结果可能与新版不同，来源和生命周期限制已记录。
- 基础 RAG 已通过本地模型和真实 OpenAI HTTP embedding 适配器两种流程验证（服务均为 loopback fixture）；真实远程模型/语义质量、其他 embedding 适配器/外部向量库、easy-rag 自动下载、复杂重排/多模态 RAG 尚未验收。Guardrails、Decision Services、复杂补偿事务、其余第三方适配器与 OpenAI Responses/音视频/图像仍保留未覆盖声明。
- SQLite 为精确全表扫描、Java 端过滤及阻塞 JDBC，异步 RAG 必须显式 offload；取消不能保证打断已开始的数据库扫描。未验证其他 JNI 平台、网络文件系统锁、同时多进程写和断电恢复。
- SQLite 会话更新只保证完整快照原子性，同会话并发 AI Services turn 需应用串行化；取消不回滚已提交历史。记忆 ID 支持 String/Integer/Long/UUID，异步需显式配置调用方 Executor，不承诺任意 Object ID 或原生异步 JDBC。
- MCP 首轮仅覆盖 2025-11-25 工具子集；会话恢复/GET SSE/modern/stdio/WebSocket/Registry 和资源提示词业务未覆盖。直接 MCP Future 取消会关闭 HTTP；AI Services 则保留已启动工具 drain 到结束/超时的上游约定。
- 已运行内部 ZeroPublisher 的官方 Publisher TCK（62 通过、14 项官方 untested），未运行所有适配器的 Subscriber/Processor/Flow TCK，不声明全组件规范认证。有限并发测试不是所有线程调度的证明；类链接仍排除三个可选宿主平台入口。

## 后续范围：跳过 Guardrails

1. 用户明确要求跳过 Guardrails 代码；后续不开展其专项移植、修复或验收，不将其标为已支持。
2. 现有源码及此前已完成的验证结果保留；该范围调整不要求删除源码或拆除已有调用路径。
3. 第六、七阶段已完成 SQLite 向量和会话记忆持久化；后续可推进 MCP/Agentic/其他集成，不再默认推进 Guardrails。
4. 继续保留真实 Java 8、JDK 17 选定回归、官方 Publisher TCK、独立 BOM 与产物审计，以及按检查点提交的约定；不自动跟随上游、推送或建立后台任务。

## 下一阶段建议：MCP 会话与传输恢复

基于已验收的工具子集，优先推进会话过期后的重新初始化、显式生命周期与服务端会话释放、SSE Last-Event-ID/恢复及 GET 通道；结合取消/并发验证，避免自动重放有副作用的工具。协议能力、真实服务互操作和 AI Services 已启动工具的取消策略需独立确定边界，不直接宣称完整 MCP 支持。继续跳过 Guardrails。

## Git 检查点

| 标记/提交 | 内容 |
|---|---|
| `39997fc70` | 首期独立工程初始化（当时 WIP） |
| `24cb6b2b4` | core 移植（当时 WIP） |
| `5393c71b0` | HTTP/OkHttp（当时 WIP） |
| `d4a1f573e` | 响应式桥接（当时 WIP） |
| `2011061b1` | OpenAI（当时 WIP） |
| `jdk8-m1` / `b8e0aafba` | 首期验收完成 |
| `5ba4ca1bd` | 第二阶段隔离分析与构建入口（当时 WIP） |
| `ccc5c191e` | AI Services、会话与工具移植（当时 WIP） |
| `d98ef0d62` | HTTP/SSE 全链路工具和取消验收（当时 WIP） |
| `jdk8-m2` / `3edba3ff0` | 第二阶段最终构建、486 个分层用例、产物消费与运行审计完成 |
| `5892eddac` | 第三阶段验收范围（当时 WIP） |
| `3d7c14bcb` | RAG 取消修复、四路径全链路与独立示例（当时 WIP） |
| `jdk8-m3` / `3c5b4b355` | 第三阶段最终 clean 构建、624 个分层用例、消费示例和运行审计完成 |
| `c939a0d9f` | 第四阶段范围与 embedding 上游回归入口（当时 WIP） |
| `be774a97e` | embedding 数据校验/取消修复、HTTP 验收和独立示例（当时 WIP） |
| `jdk8-m4` / `3dcedfd07` | 第四阶段最终 clean 构建、716 个分层用例、HTTP RAG 消费及运行审计完成 |
| `e42da1340` | 第五阶段独立官方 TCK 入口（当时 WIP） |
| `e9fffab93` | 引用释放、prologue demand/取消修复与并发验收（当时 WIP） |
| `jdk8-m5` / `6735feafe` | 第五阶段最终 clean 构建：788 项通过，另列 14 项官方 untested；消费示例/审计通过 |
| `bef62f318` | 按用户要求跳过 Guardrails |
| `a15eec209` | 第六阶段 SQLite 模块/BOM/验收入口（当时 WIP） |
| `9cf6e1f58` | SQLite 事务实现、类型/并发/RAG 验收与跨进程消费 |
| `jdk8-m6` / `adb3edcad` | 第六阶段最终结果：811 项通过，另列 14 项官方 untested；七模块审计与 SQLite 重启消费通过 |
| `e732ea9dd` | 第七阶段会话记忆范围（当时 WIP） |
| `248c8f71e` | SQLite 会话存储、共享事务、取消修复和跨进程工具历史恢复 |
| `jdk8-m7` | 第七阶段最终结果：903 项通过，另列 14 项官方 untested；记忆/RAG 重启消费及审计通过 |

| `0a12fe508` | 第八阶段 MCP 工具子集范围（当时 WIP） |
| `92546d11d` | MCP Java 8 客户端/OkHttp 工具桥接、24 个验收、23 个上游类与独立 BOM 示例 |
| `jdk8-m8` | 第八阶段最终 clean：1085 项通过、14 项官方 untested；八模块审计及 MCP 消费通过 |

下一轮先读此文件、`COMPATIBILITY.md` 和 `git status`。一次性转换工具只用于原始快照，不能对已移植源码重跑作为升级方案。

## 第三阶段历史检查点（当时 WIP，现已完成）

- 已从干净的 `jdk8-m2` 恢复；外置盘挂载，约 69 GiB 可用。
- 不新增集成模块，沿用六模块兼容构建；增加确定性本地 RAG 全链路和选定上游回归。
- 此检查点当时尚未验收；当前支持范围以上方最新阶段最终结果为准。

### 第三阶段实现检查点（当时 WIP，现已完成）

- 加入 `RagJdk8Test`：确定性 embedding、多文档分段/index/元数据、过滤/topK/空结果、JSON/文件恢复及删除、文件加载错误、同步/Future/回调/Publisher 提示词注入和来源、记忆原文保留、阻塞 offload 及失败传播。
- 加入 `RagOpenAiJdk8Test`：四种调用路径通过 loopback HTTP/SSE 发送真实 RAG 请求，并验证上下文和 HTTP 调用释放；不依赖真实模型。
- 已复现并修复两个取消断点：检索器 `thenApply` 到 embedding 的回传、AI Services 的异步 memory/augmentation 组合链。补充 Future/Publisher/等待 memory 期间的取消测试，防止继续调用模型或检索器。
- 聚焦验收：15 个 `RagJdk8Test` 在真实 Java 8 下通过；此前完整构建及新增 HTTP/SSE 4 个用例通过。随后已完成含全部新增边界用例的最终 clean 构建，结果见上方和 milestone-3 快照。
- 独立 BOM 的 `RagExample` 已在 Java 8 输出 `Java 8 RAG OK; answer: 7 yuan; source: apple price is 7 yuan.`；关键词向量仅用于验证流程，不代表真实语义质量。

## 第四阶段历史检查点（当时 WIP，现已完成）

- 从干净的 `jdk8-m3` 恢复，外置盘挂载、可用约 69 GiB。
- 沿用六模块生产依赖；新增 embedding 请求/响应、listener、异步/重试等选定上游回归。
- 本阶段目标：loopback Embeddings API 的批量/维度/token usage/错误/超时/取消，HTTP embedding 到检索及 AI Services 的完整闭环，独立 BOM 消费示例。
- 当时支持范围仍以第三阶段验收为准；现已完成第四阶段验收，未使用真实网络模型或服务凭据。

### 第四阶段实现检查点（当时 WIP，现已完成）

- `EmbeddingOpenAiJdk8Test` 新增 19 个真实 Java 8 用例：批量顺序及 token 合并、请求覆写/维度/Base64/headers/listener、错误/重试/超时、全部批次/兄弟任务取消，及 HTTP embedding -> 存储恢复 -> 四条 AI Services HTTP/SSE 路径。
- 已复现并修复：响应 index 被忽略；响应数量与索引/向量缺失未校验；底层 HTTP Future 独立取消导致批量结果一直等待。同步/异步共用响应校验，全部无 index 的兼容提供方仍保留 wire 顺序。
- 新增独立 BOM 的 `HttpRagExample`，使用真实 OpenAI embedding/chat 适配器访问 loopback；显式自定义 OkHttp 时由消费 POM 声明 BOM 管理的 OkHttp 模块。
- 最新 clean reactor 已通过 89 个 Java 8 / 627 个 JDK 17 用例，0 失败/错误/跳过；随后独立消费项目三个示例及运行审计均已通过，详见上方最终结果和 milestone-4 快照。

## 第五阶段历史检查点（当时 WIP，现已完成）

- 从干净的 `jdk8-m4` 恢复；外置盘挂载且约有 69 GiB 可用。
- 新增独立 `reactive-tck` 测试模块，在真实 Java 8 执行官方 TCK 1.0.4 的 PublisherVerification；生产 Reactive Streams API 仍为 1.0.2，不新增生产依赖。
- 使用新 TCK 修复旧版测试问题，不覆盖官方规则、不限制 maxElements、不排除 required 测试。官方标为 untested 的跳过项将单独记录，不计为通过。
- 此检查点当时尚待完成后续验收；现已完成，详见上方最终结果。

### 第五阶段实现检查点（当时 WIP，现已完成）

- 官方 TCK 1.0.4 同线程/异步两组 PublisherVerification：76 项中 62 通过、14 项官方 untested 跳过；无 required/optional 规则跳过、无失败。使用惰性有界源处理超大需求用例，未修改官方规则或限制 maxElements。
- TCK 已复现并修复取消时保留 subscriber 的问题；ZeroPublisher 在取消/终止时清除引用，非法 demand 错误包含规则 3.9 信息。
- Java 8 新增 10 个并发/生命周期验收：100 轮多生产者/多请求者、300 轮终止竞争、onNext 与 fail/cancel 的确定性竞争、终止后 GC、原始事件流 demand/RAG 失败竞争，以及真实 SSE 溢出与 30 轮 request/cancel 释放。
- 修复原始事件 Publisher 在 prologue 尚未交接真实 Subscription 时忽略非正 demand 的问题，以及 onSubscribe 立即取消后仍启动 RAG；终止统一去重，清理 DeferredSubscription 的 delegate/callback 引用。
- 聚焦 Java 8 并发验收通过后，已完成最终 clean 构建与快照。生产依赖/BOM 支持模块数量不变，TCK/TestNG 仅用于独立测试模块。

## 第六阶段历史检查点（当时 WIP，现已完成）

- 用户批准 SQLite 持久化阶段；Guardrails 继续跳过。仓库从干净状态恢复，外置盘挂载、约 69 GiB 可用。
- 新增独立 `langchain4j-sqlite`，兼容 reactor/BOM/验收/消费审计纳入该模块；此检查点当时尚未标记支持，现已完成上方最终验收。
- 首版方案：文件数据库、事务批量 upsert、类型保真元数据、Java 精确余弦全表扫描/topK/过滤和删除；不使用 sqlite-vec 扩展或 ANN 索引。
- 候选 Xerial JDBC 3.53.4.0 的官方 POM 使用 release 8，此检查点当时仍待产物/Java 8/JNI 验收，现已通过。依赖与 native 临时文件均留在外置盘。
- JDBC 为阻塞接口，原生 async 不作虚假实现，异步 RAG 显式 offloadBlocking(true)。

### 第六阶段实现检查点

- `SqliteEmbeddingStore` / `SqliteCodec` 已实现文件路径 builder、维度固定、事务 upsert/删除、只读快照、类型保真编码和精确 topK/过滤；连接按操作关闭，close 等待在途操作且不删除文件。
- 20 个 Java 8 SQLite 用例验证 JNI/重开/所有 metadata 类型/大 Unicode/upsert/空批次/过滤/排序/维度/回滚/损坏/schema/锁/并发及内存参考结果对照；3 个 SQLite RAG 用例覆盖四条 loopback HTTP/SSE 路径和 embedding 阶段取消。
- 首次独立消费验证发现误加的 dependencyManagement 项覆盖了 BOM，已修正；最终 clean 构建通过。驱动有 126 个 Java 8 基础类；GraalVM/Java 9 类仅在多版本目录，不需要新增基础类链接排除。
- `HttpRagExample write/read` 在两个实际 Java 8 进程完成 OpenAI HTTP embedding 入库和重开后的异步 RAG；writer 2 个段落，reader 仅发 1 次查询 embedding，无重新入库。
- 当前最终结果：122 Java 8 / 627 上游 / 62 Publisher TCK 通过；14 项官方 untested 单独列出。31 JAR/5978 基础类审计、4752 类链接通过，保持 3 个既有可选平台排除。最终快照已保存为 milestone-6.json，阶段标记 jdk8-m6。

## 第七阶段历史检查点（当时 WIP，现已完成）

- 从干净的 `jdk8-m6` 恢复；外置盘挂载、约 69 GiB 可用；Guardrails 继续跳过。
- 沿用七模块、SQLite JDBC 3.53.4.0，新增同模块 SqliteChatMemoryStore，复用事务/连接生命周期，不新增生产依赖。
- 计划：类型隔离的稳定会话 ID、核心消息 JSON codec、会话整体事务替换/删除、重启与会话隔离/工具消息/RAG 验收。
- 默认仅同步；调用方显式配置 asyncExecutor 才启用阻塞 JDBC 的异步 offload，Executor 生命周期归调用方。
- 该检查点当时尚未验收；现已完成上方第七阶段最终验收。

### 第七阶段实现检查点

- 新增同模块 SqliteChatMemoryStore：类型隔离 ID、版本化核心消息 JSON、整段事务替换/删除、独立可变读结果、默认同步与显式 Executor offload。抽取 SqliteDatabase 共用连接/事务/close 生命周期，SQLite 向量回归保持通过。
- 新增 27 个真实 Java 8 用例：15 个存储/异步/并发，3 个窗口记忆取消，7 个 AI Services HTTP/SSE/工具/会话，2 个 SQLite RAG+记忆共存；新增 4 个上游消息 codec/类型测试类。
- 测试复现并修复窗口记忆消息投影与 addAsync 组合的取消断点，以及 AI Services 等待记忆组装时不传递取消而继续写入排队问题的情况。只修改记忆链路，Guardrails 逻辑未改动。
- 两个独立 Java 8 进程：memory writer 保存 4 条含工具调用/结果的消息，memory reader 恢复历史后向模型发送 5 条并保存为 6 条，向量及会话共用 DB。
- 完整 clean 构建已通过：149 个 Java 8、692 个 JDK 17、62 个 Publisher TCK；14 项官方 untested 单独列出。31 JAR/5986 基础类审计、4756 类链接通过；最终快照已保存为 milestone-7.json，阶段标记 jdk8-m7。

## 第八阶段历史检查点（当时 WIP，现已完成）

- 从干净的 jdk8-m7 恢复，外置盘挂载、约 69 GiB 可用。Guardrails 继续跳过。
- 审计固定基线 MCP：主要障碍为 JDK HTTP/WebSocket、Flow、record/sealed、新语法和 Java 9+ Future/Atomic API；不新增生产依赖版本。
- 目标：第八个生产模块 langchain4j-mcp，保留客户端/工具 provider/executor，HTTP 改为 OkHttp，首轮只验收 MCP 2025-11-25 的 tools/list、tools/call、JSON/非恢复 POST SSE 和 AI Services 工具循环。
- stdio/WebSocket/Registry 源码保留但不进入兼容产物；modern protocol、GET subsidiary SSE/恢复订阅/自动会话重连和资源/提示词专项验收不在本轮支持声明内。
- 该检查点当时尚未验收；现已完成上方最终 clean 验收。

### 第八阶段实现检查点（当时 WIP，现已完成）

- MCP 112 个生产源码文件已使用 --release 8 编译；默认 HTTP 传输以 OkHttp 替换 JDK HTTP，首轮固定 2025-11-25 工具协议。
- 新增 24 个真实 Java 8 验收用例全部通过：HTTP/SSE、分页/schema、错误、超时/直接取消/close/初始化失败释放、并发 ID、值类 JSON 和四条工具循环。
- 增加 23 个上游 MCP 测试类，共 158 项；JDK 17 选定回归总计 850 项通过，0 失败/错误/跳过。没有删改这些原始测试。
- 新增联调用例最初要求 AI Services 立即取消工具，失败后核对源码确认上游有意 drain 已启动工具以保留补偿结果；保留此约定，最终分别验证直接 MCP 取消与 AI Services 取消后不再调用模型。未修改 Guardrails 或通用补偿逻辑。
- 初始化失败遗留 HTTP 的清理路径已补齐，pending registry 统一终止清理；McpExample 与 BOM 已纳入最终构建，随后完整 clean/消费/运行时审计全部通过，结果见上方和 milestone-8 快照。

## 第九阶段检查点（WIP）

- 目标：MCP 会话过期重新初始化、显式 DELETE 释放、可选 GET SSE 通道、POST/GET SSE 断线恢复与 Last-Event-ID；并发、取消、超时及资源释放全部验收后提交阶段标签。
- 从干净的 jdk8-m8 恢复，外置盘已挂载、约 69 GiB 可用。继续固定 MCP 2025-11-25；Guardrails 跳过，不连接外部服务器或读取其他凭据。
- 约束：恢复不重发任何原始 POST；失效会话的在途请求明确失败，新请求等待共享初始化。SSE 恢复只 GET 同一流游标；重试有间隔/次数及请求总时限。关闭停止全部重试并以有界 DELETE 尝试释放已知会话。
- 当前尚未验收；已支持结果仍以 m8 为准。最终需保留完整 clean 构建、真实 Java 8/上游/TCK、独立 BOM 与产物审计。

### 第九阶段实现检查点（WIP）

- 实现会话代际、共享初始化、404 在途失败/后续新请求等待、能力和缓存失效；使用独立负数恢复请求 ID，不重发原 POST。
- 新增 SSE 读取器，支持 UTF-8/BOM、CR/LF/CRLF、完整事件游标提交、retry 字段、空/无效/未完成事件；POST 断线仅 GET 恢复，GET 子通道独立游标/dispatcher、405 降级和有界连续失败重试。
- 新增 terminateSession/有界 DELETE、幂等 close、调用方客户端所有权与自建资源关闭。请求总 deadline 跨恢复生效，取消通知绑定原会话；通过 one-shot body 禁止 OkHttp 503/421 等隐式 POST 重发。
- 聚焦真实 Java 8：新增 31 项恢复验收和原有 24 项 MCP 均已通过；随后继续补强 close 截止与握手并发收尾，并将独立 BOM 的 MCP 示例增加 recovery 模式，待最终 clean 验收。
- 记录诊断：成功响应后的 Call.cancel 清理已修正；原生 OkHttp 对照定位另一个 EOF 为 Java 8 HttpServer 空 GET/DELETE 请求体未初始化造成夹具连接关闭，夹具已修复，未以重试 POST 掩盖错误。
