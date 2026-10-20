# Java 8 移植进度

## 当前状态：第七阶段 SQLite 会话记忆持久化进行中（WIP，2026-10-20）

- 仓库：`/Volumes/Nas/codex-work/langchain4j-jdk8`；分支 `jdk8/main`；当前完成标记 `jdk8-m6`；历史标记 `jdk8-m1` 至 `jdk8-m5`。
- 固定上游基线：`7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。相邻原始仓库未修改；完整 Git 历史独立保存，不共享对象文件。
- Maven 坐标：`dev.langchain4j.jdk8:*:1.23.0-jdk8-SNAPSHOT`，兼容 BOM 现覆盖七个生产模块。已安装到外置盘 Maven 缓存；未远程推送或公共发布。

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

## 最终验证

执行 `./jdk8/build.sh -o`（reactor 离线使用外置盘已缓存依赖），完整 clean install、独立 BOM 消费项目、字节码审计、真实 Java 8 类链接、AI Services 模拟对话、独立 RAG、HTTP embedding RAG 和 SQLite 跨进程示例全部通过。

| 验证 | 第六阶段结果 |
|---|---|
| 七个模块生产源码 | JDK 17 `--release 8` 构建通过 |
| 真实 Java 8 验收 | 122 个用例，0 失败/错误/跳过；JDK 1.8.0_462 |
| 选定上游及现代反射回归 | 627 个用例，0 失败/错误/跳过；JDK 17.0.13 |
| 官方 Publisher TCK / Java 8 | 76 项：62 通过、14 官方 untested 跳过；0 失败/错误，无 required/optional 跳过 |
| 字节码和禁止引用审计 | 31 个 JAR 文件、5978 个基础类通过；含项目构建/安装副本 |
| Java 8 类及成员类型加载 | 4752 个类通过；3 个可选 Android/Conscrypt/OSGi 入口排除 |
| 独立 BOM 消费示例 | Java 8 通过 AI Services 返回 `Java 8 OK` |
| 独立 BOM RAG 示例 | Java 8 返回 `7 yuan` 和对应 apple 文档来源 |
| 独立 BOM HTTP RAG 示例 | Java 8 返回 `7 yuan`；入库和查询共 2 次 embedding HTTP 请求 |
| SQLite 跨进程消费 | writer 2 段落持久化；独立 Java 8 reader 查询并返回 `7 yuan`，仅 1 次查询 embedding |
| SQLite native 引擎 | Xerial 3.53.4.0 / SQLite 3.53.4，Java 8/macOS/aarch64 通过 |
| 模型资源检查 | 兼容 JAR 只包含配套 1.9.3 模型，未打入 2.5.0 模型 |
| diff/shell 检查 | `git diff --check`、`sh -n jdk8/build.sh` 通过 |

结果快照：`verification/milestone-6.json`；历史快照保留为 `verification/milestone-5.json`（99 / 627 / 62 TCK）、 `verification/milestone-4.json`（89 / 627）、 `verification/milestone-3.json`（70 / 554）、 `verification/milestone-2.json`（51 个 Java 8 / 435 个上游用例）和 `verification/milestone-1.json`（25 / 228）。当前报告位于 `acceptance`、`upstream-tests`、`reactive-tck` 三个模块的 `target/surefire-reports`。这些是选定回归，不代表上游全量/Kotlin 测试已运行。

本阶段保留 627 个 JDK 17 上游回归和官方 Publisher TCK 两组；新增 20 个 SQLite / 3 个 SQLite RAG 的真实 Java 8 用例，以及独立 JVM 重启消费验证。工具夹具按原始 arg0/arg1 参数约定编译，生产参数名保留不变。

## 已知边界

- Java 8 的 Flow 签名替换、record/sealed 降级和线程模型差异继续适用；具体约定见 `COMPATIBILITY.md`。
- OpenNLP 2.x 不能运行于 Java 8。使用 1.9.5 和官方 0.35.0 历史中的 1.9.3 句子模型，未修改模型版本绕过校验；Java 8 和 6 个原始分割用例通过。1.9.x 已结束维护，模型结果可能与新版不同，来源和生命周期限制已记录。
- 基础 RAG 已通过本地模型和真实 OpenAI HTTP embedding 适配器两种流程验证（服务均为 loopback fixture）；真实远程模型/语义质量、其他 embedding 适配器/外部向量库、easy-rag 自动下载、复杂重排/多模态 RAG 尚未验收。Guardrails、Decision Services、复杂补偿事务、其余第三方适配器与 OpenAI Responses/音视频/图像仍保留未覆盖声明。
- SQLite 为精确全表扫描、Java 端过滤及阻塞 JDBC，异步 RAG 必须显式 offload；取消不能保证打断已开始的数据库扫描。未验证其他 JNI 平台、网络文件系统锁、同时多进程写和断电恢复。
- 已运行内部 ZeroPublisher 的官方 Publisher TCK（62 通过、14 项官方 untested），未运行所有适配器的 Subscriber/Processor/Flow TCK，不声明全组件规范认证。有限并发测试不是所有线程调度的证明；类链接仍排除三个可选宿主平台入口。

## 后续范围：跳过 Guardrails

1. 用户明确要求跳过 Guardrails 代码；后续不开展其专项移植、修复或验收，不将其标为已支持。
2. 现有源码及此前已完成的验证结果保留；该范围调整不要求删除源码或拆除已有调用路径。
3. 第六阶段已完成 SQLite 向量持久化；后续可继续 SQLite 会话记忆持久化，或另行选择 MCP/Agentic/其他集成，不再默认推进 Guardrails。
4. 继续保留真实 Java 8、JDK 17 选定回归、官方 Publisher TCK、独立 BOM 与产物审计，以及按检查点提交的约定；不自动跟随上游、推送或建立后台任务。

## 下一阶段建议：SQLite 会话记忆持久化

在现有 SQLite 依赖审计和事务基础上，逐步验证持久化 ChatMemoryStore、会话隔离、消息/工具结果序列化、清理和重启恢复，再与 AI Services/RAG 联动；Guardrails 继续跳过。该建议尚未开始实现。

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
| `jdk8-m6` | 第六阶段最终结果：811 项通过，另列 14 项官方 untested；七模块审计与 SQLite 重启消费通过 |

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

## 第七阶段检查点（WIP）

- 从干净的 `jdk8-m6` 恢复；外置盘挂载、约 69 GiB 可用；Guardrails 继续跳过。
- 沿用七模块、SQLite JDBC 3.53.4.0，新增同模块 SqliteChatMemoryStore，复用事务/连接生命周期，不新增生产依赖。
- 计划：类型隔离的稳定会话 ID、核心消息 JSON codec、会话整体事务替换/删除、重启与会话隔离/工具消息/RAG 验收。
- 默认仅同步；调用方显式配置 asyncExecutor 才启用阻塞 JDBC 的异步 offload，Executor 生命周期归调用方。
- 现阶段尚未验收，支持范围仍以第六阶段为准。

### 第七阶段实现检查点

- 新增同模块 SqliteChatMemoryStore：类型隔离 ID、版本化核心消息 JSON、整段事务替换/删除、独立可变读结果、默认同步与显式 Executor offload。抽取 SqliteDatabase 共用连接/事务/close 生命周期，SQLite 向量回归保持通过。
- 新增 27 个真实 Java 8 用例：15 个存储/异步/并发，3 个窗口记忆取消，7 个 AI Services HTTP/SSE/工具/会话，2 个 SQLite RAG+记忆共存；新增 4 个上游消息 codec/类型测试类。
- 测试复现并修复窗口记忆消息投影与 addAsync 组合的取消断点，以及 AI Services 等待记忆组装时不传递取消而继续写入排队问题的情况。只修改记忆链路，Guardrails 逻辑未改动。
- 两个独立 Java 8 进程：memory writer 保存 4 条含工具调用/结果的消息，memory reader 恢复历史后向模型发送 5 条并保存为 6 条，向量及会话共用 DB。
- 完整 clean 构建已通过：149 个 Java 8、692 个 JDK 17、62 个 Publisher TCK；14 项官方 untested 单独列出。31 JAR/5986 基础类审计、4756 类链接通过；待保存最终快照和阶段标记。
