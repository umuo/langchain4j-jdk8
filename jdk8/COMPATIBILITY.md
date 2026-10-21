# 兼容差异与验证边界

## 公共接口

- 包名保持 `dev.langchain4j.*`；Maven groupId 为 `dev.langchain4j.jdk8`，版本统一 `1.23.0-jdk8-SNAPSHOT`。
- `java.util.concurrent.Flow.Publisher/Subscriber/Subscription` 改为 `org.reactivestreams` 对应接口。这是有意的源代码和二进制签名差异；回调式流式 API 保留。
- record 转为普通 final 值类，保留组件访问器、构造校验、equals/hashCode/toString，并为 JSON 添加显式构造/字段元数据。它们不再是 JVM record，反射 `isRecord()` 结果会不同。
- sealed/non-sealed/permits 声明移除，Java 8 无法强制限定继承；Java 17 消费者自行声明的 sealed 类型仍通过反射探测支持。Java 8 多态 POJO 使用 `@JsonSubTypes` 等显式注解。
- `@Deprecated` 的 since/forRemoval 元数据去除；弃用标记保留。
- 自定义集合工厂保留拒绝 null、拒绝重复 Map/Set 工厂项和不可修改的语义；`copySet` 允许输入重复项。集合迭代顺序可能与官方 JDK 工厂不同。

## HTTP、响应式与线程

- OpenAI 默认传输改为 OkHttp 4.12.0；保留自定义客户端 builder、base URL、超时和请求配置。JDK HTTP Client 不进入生产依赖。
- OkHttp 的 SSE 读取在 dispatcher 线程执行，与 JDK 客户端的线程模型不同；Java 8 不承诺无阻塞网络读取。取消、完成、失败或缓冲溢出会释放 HTTP 调用。
- `mutiny-zero` 1.3.1 的 Java 17 类替换为内部有界 Reactive Streams 桥接，覆盖需求量、取消、溢出、串行分发和单次终止；第五阶段已运行该桥接的官方 PublisherVerification，具体范围与跳过项见后文，不代表所有响应式组件均被完整验证。
- Reactive Streams 使用 1.0.2 的稳定接口；1.0.4 附带引用 Java 9 Flow 的适配类，不引入 Java 8 生产依赖。
- 保留虚拟线程反射探测和普通线程回退；JDK 8 使用平台线程。

## 依赖与验证

- 验证基线：Jackson 2.18.6、SLF4J 2.0.17、JSpecify 1.0.0、JTokkit 1.1.0、OkHttp 4.12.0。后续升级需重新审计 API、字节码与消费项目。
- 所有移植生产源码以 JDK 17 `--release 8` 编译。JAR 审计检查基础类版本、禁止类型引用和官方模块泄漏；多版本 JAR 的 `META-INF/versions/` 与 `module-info.class` 按 Java 8 加载规则排除。
- 真实 Java 8 链接检查加载全部基础运行时类及其成员类型；仅排除 OkHttp 的两个可选 Android/Conscrypt 类和 OpenNLP 的一个可选 OSGi 入口类，因本环境不安装对应平台；OSGi 场景需要宿主框架提供相关 API。这不是对全部方法体的穷尽链接证明。
- JSpecify 含 `ElementType.MODULE` 元数据，Java 8 API 编译时有两个注解警告；Java 8 运行和测试已验证通过。SLF4J 默认无日志 provider，消费应用可自行配置兼容的日志实现。
- Java 8 独立验收与 JDK 17 上游选定回归分开执行。上游完整测试集及 Kotlin 测试尚未全量迁移或运行，不以过滤后的结果声称全量通过。
- 已验证 OpenAI Chat Completions 的同步、异步、回调/响应式流式请求及错误、超时和取消；工具定义/调用结果消息和结构化格式已验证请求与响应转换。第二阶段已验证 AI Services 的自动工具执行循环，包括同步、Future、回调式及响应式流式路径。
- OpenAI 模块其余能力（Responses API、音视频、图像、真实远程服务行为）虽然随模块编译及类加载通过，尚未完成独立业务验收。Embeddings HTTP 适配器的第四阶段验收见后文；测试仅使用 loopback 模拟服务，无外部密钥。


## 第二阶段：AI Services、记忆与工具（jdk8-m2）

- `langchain4j` 主模块加入兼容 reactor/BOM，仍使用统一的兼容坐标和版本。默认接口方法通过 MethodHandles 调用：Java 8 使用 Lookup 构造路径，较新 JVM 通过反射使用 privateLookupIn；支持继承、非 public 接口、参数和原始异常。受限安全策略仍需允许相关反射操作。
- 元数据 provider 从 Java 9 ServiceLoader stream/Provider 改为 Java 8 迭代，保留自定义实现优先于默认反射工厂。Java 17 下保留 record 元数据的反射视图，避免在 Java 8 上直接引用 RecordComponent。
- 支持注解提示词、结构化 POJO、Future、TokenStream、Reactive Streams Publisher，及 MessageWindowChatMemory 的同步/异步会话隔离和 ChatMemoryAccess 查询/清理。异步链路仍要求组件/存储提供原生 async 接口；不会把所有阻塞组件自动伪装成非阻塞实现。
- 已验证自动工具循环的四条调用路径、结果消息回传、并发工具、工具失败、最大轮次限制，以及 Future/Publisher 取消后 HTTP 调用释放。完整复杂补偿事务、Guardrails、Decision Services 和第三方适配器尚未全量业务验收。
- 内部 Tube 新增 whenCancelled，与任意终止 whenTerminates 区分；正常完成或错误不会触发取消专用回调。新增公开 record 替代类型保留显式 JSON 字段/构造元数据。
- 第二阶段只验证了高层 RAG、文档加载器和 InMemoryEmbeddingStore 的编译/类链接；第三阶段增加的业务验收见下节。
- 上游工具测试的输入显式使用 arg0/arg1，因此独立上游回归模块关闭参数名保留以匹配原始测试夹具；生产模块和 Java 8 消费用例继续使用 `-parameters`。未修改或跳过这些原始测试。

### OpenNLP 与英语句子模型

- 实测 OpenNLP 2.3.3 为 Java 17 字节码，2.0.0/2.1.1 为 Java 11；选择基础类为 Java 8 的 OpenNLP 1.9.5。旧依赖只用于满足主模块现有句子分割 API，不代表推荐其用于新 NLP 系统。
- 原默认模型声明 OpenNLP-Version=2.5.0，1.9.5 明确拒绝加载。兼容分支恢复官方 `0.35.0` tag 的 `opennlp-en-ud-ewt-sentence-1.0-1.9.3.bin`（Git blob `d3a2779233fcd2d3017b5d3de6ca14e8fd3e5cc7`），未伪造模型版本或跳过格式校验。
- 2.5.0 模型仍保留在源码中便于上游对比，排除在兼容 JAR 资源之外。使用新版/自定义模型时须自行确认与 1.9.5 格式兼容；分句结果可能与新版模型不同。Java 8 实际分句及 6 个原始分割测试通过。
- OpenNLP 官方已宣布 1.9.x 于 2026-08-10 结束维护，不再提供修复或安全更新，这是 Java 8 分支的具体已知限制。[官方生命周期说明](https://opennlp.apache.org/news/news-2026-08-10.html)

## 第三阶段：基础 RAG（jdk8-m3）

- 沿用六个生产模块、原包名与兼容 BOM；本阶段没有增加生产依赖，也没有修改上游固定基线。
- 确定性本地 embedding 验证多文档 -> 段落分割 -> index/元数据继承 -> 入库 -> 分数阈值/topK/元数据过滤 -> 提示词注入 -> AI Services 答复和来源。同步、Future、TokenStream、Reactive Streams Publisher 均经过 Java 8 验收，四条路径另有 loopback OpenAI HTTP/SSE 联动与连接释放验证。
- JSON 和文件恢复保留向量及 TextSegment 元数据，恢复后过滤删除不影响原存储；测试覆盖损坏 JSON。文件加载覆盖正常、空文档、解析失败、缺失路径；批量加载按原接口约定跳过失败项，单文件加载抛出包装异常。不代表全部文件系统/URL/classpath 加载器边界已覆盖。
- 修复异步检索中 embedding `thenApply` 与 AI Services 的 memory/augmentation 组合链取消断点；Future 和 Publisher 取消能到达支持取消的底层任务。等待异步 memory 读取时取消会取消读取并避免启动后续检索。组件需自行实现实际 I/O 中止，普通 `CompletableFuture.cancel` 无法保证终止阻塞 I/O。
- 验证 `storeRetrievedContentInChatMemory(false)` 保留问题原文而模型收到检索上下文；默认历史存储约定保持不变。验证 embedding 失败不会写入部分入库结果，检索失败不会继续调用对话模型。
- 保留原生 async 优先和显式 `offloadBlocking(true)` 的约定，已在真实 Java 8 上验证普通线程回退。示例的关键词向量仅证明流程，不验证真实语义质量。OpenAI embedding HTTP API、第三方向量库、easy-rag 自动模型下载、复杂重排/多模态 RAG 和真实网络服务仍未验收。
- 本阶段 70 个真实 Java 8 用例和 554 个 JDK 17 选定回归全部通过；增加了原始段落分割、检索器（含 async）、默认 augmentor、注入器、聚合器/融合、路由/查询转换、内存过滤和异步存储 RAG 测试。未删改原始测试或跳过失败用例；完整上游测试集仍不在本报告范围内。

## 第四阶段：OpenAI Embeddings HTTP 与 RAG（jdk8-m4）

- `OpenAiEmbeddingModel` 的同步、原生 async、分批输入与输入顺序、响应 model/token usage 聚合、请求参数覆写、维度、Base64 little-endian float 解码、listener、base URL/鉴权/组织/项目/custom headers/query 均经真实 Java 8 + OkHttp loopback 验证，无新增生产依赖。
- 同步/异步共用批次响应校验：每个批次必须返回与输入等量的非空向量；有 index 时按 index 恢复输入顺序，拒绝缺失/重复/越界 index，避免向量配错文本。全部省略 index 的兼容提供方仍使用 wire 顺序。相比固定基线，这是有意的行为修复；损坏响应现在尽早抛出 `IllegalArgumentException`，不再返回部分或错位数据。异步通过异常 Future 报错。
- 此处维度验收验证配置和请求传输及对应 fixture 向量长度，不强制核对所有提供方返回维度，也不证明真实模型的语义质量。
- HTTP 400 不重试，429 同步/异步按 maxRetries 重试；超时与显式取消释放 OkHttp 调用。任一批次的失败取消兄弟任务并保留原错误；底层 HTTP Future 独立取消也会终止 caller Future，修复原先一直等待的问题，不重试取消。调用方主动 cancel 仍保留取消状态；独立传输取消作为带 CancellationException 原因的异常结果传递。
- 文档入库和 JSON 恢复后，使用 HTTP embedding 检索、元数据过滤、上下文注入，完成同步/Future/TokenStream/Publisher 四条真实 HTTP/SSE 模拟对话路径；Future/Publisher 在 query embedding 阶段取消会释放 HTTP，避免启动 chat。embedding 查询失败同样不会继续调用 chat；损坏入库响应不会写入部分向量。
- 独立 BOM `HttpRagExample` 在 Java 8 下验证 embedding 和 chat 两个真实适配器；模拟服务刻意乱序返回向量以验证 index 对齐。显式自定义 OkHttp 的源码示例新增 BOM 管理的 OkHttp 模块编译依赖，默认 OpenAI 传输依赖配置未改变。
- 89 个真实 Java 8 / 627 个 JDK 17 选定回归全部通过；新增原始 embedding model/request/response/维度/listener、OpenAI 参数/Base64/async/retry 测试。真实远程服务、真实模型质量、其他 embedding 适配器及外部向量库仍未验收。

## 第五阶段：Publisher TCK 与并发稳定性（jdk8-m5）

- 新增 `jdk8/reactive-tck` 独立测试模块，由兼容构建默认运行；使用官方 `reactive-streams-tck:1.0.4`、TestNG 7.3.0，在实际 Java 8 下执行。生产 API 仍为 `reactive-streams:1.0.2`；TCK、TestNG 和其依赖不进入生产 BOM 或运行时消费项目，测试不使用 Flow TCK。
- 选择新版 TCK 以包含其测试修复；未覆盖/修改官方测试方法，没有缩减 maxElements，没有跳过 required/optional 用例。使用同线程和异步两个实际 ZeroPublisher fixture：有限流允许缓冲；超大需求测试使用惰性源、最多一个元素超前于已交付信号，真实 State Subscription 原样交给 TCK，不添加 request/cancel 代理。
- 两组 PublisherVerification 共 76 项，62 通过、14 项官方 `untested_*` 跳过。必测、可选与随机串行信号测试均通过。每组跳过的是 spec106、107、108、109、110、304、305，原因均为官方 `Not verified by this TCK.`；这些不计为通过，也不声称其已被完整证明。来源：[官方规范与 TCK](https://github.com/reactive-streams/reactive-streams-jvm)、[发布说明](https://github.com/reactive-streams/reactive-streams-jvm/blob/master/RELEASE-NOTES.md)。
- TCK 复现了外部源保留 emitter 时 subscriber 无法回收的问题；State 现在取消/终止时清除 subscriber 引用，保留已开始的 onNext 最多一次交付和串行终止语义。补充完成/错误后的 GC 验收。非法需求量错误现在包含规则 3.9 信息。
- 原始 `Publisher<AiServiceStreamingEvent>` 在 RAG/prologue 尚未交接真实 subscription 时立即拒绝非正 demand 并取消等待任务；onSubscribe 中立即 cancel 不启动检索。DeferredSubscription 对终止信号去重，并在取消/终止时释放 delegate/callback 引用；正常交接后的 demand 仍由实际 subscription 处理。
- Java 8 并发验收覆盖 100 轮双生产者/双请求者的 1000 元素流、300 轮 complete/fail 竞争、onNext 中 fail/cancel、100 轮非法 demand 与 RAG 失败竞争、完成/错误后的回收。真实 loopback SSE 覆盖无 demand 缓冲溢出和 30 轮 request/cancel 竞争后的 HTTP 释放。
- 本阶段 TCK 验证对象是内部 ZeroPublisher 的 Publisher/Subscription，未对所有适配器执行 SubscriberWhitebox/Blackbox、Processor 或 Flow TCK；不声明所有生产 Publisher/Subscriber 均获得完整规范认证。有限并发压力测试也不是所有线程调度或无限运行的证明。

## 范围调整：跳过 Guardrails

用户明确要求跳过 Guardrails 代码。后续不开展其专项移植、修复或业务验收；保留现有源码及调用路径，不据此宣称 Guardrails 已支持 Java 8。前五阶段已完成的验证结果保持原有范围。

## 第六阶段：SQLite 持久化向量存储（jdk8-m6）

- 新增 `langchain4j-sqlite`，包为 `dev.langchain4j.store.embedding.sqlite`，实现 `EmbeddingStore<TextSegment>` 与 `AutoCloseable`；这是本分支新增模块，非上游既有 SQLite 模块的回移植。兼容 reactor/BOM 现覆盖七个生产模块。
- 选择 Xerial `sqlite-jdbc:3.53.4.0`，官方 POM 基础编译目标为 release 8，实测 JAR 126 个基础类为版本 52，真实 Java 8/macOS/aarch64 成功加载 SQLite 3.53.4 原生引擎。Java 9/GraalVM 类位于多版本目录，未增加基础类链接排除。来源：[固定版本官方 POM](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/pom.xml)。
- 默认驱动 JAR 含多个平台 native 库，构建把其提取目录固定在外置工作区 `jdk8/native-tmp`，不向系统盘迁移依赖缓存。库本身不修改全局 JVM 属性，消费应用可用 `-Dorg.sqlite.tmpdir` 显式配置。来源：[官方提取配置](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/USAGE.md)。
- 文件路径 API、每文件一个集合，首次非空写入固定维度，removeAll 保留维度；拒绝不匹配维度、空/非有限向量和空白 ID。ID 使用 upsert，同一批重复 ID 最后一个值生效。空/null 输入批次遵循核心 no-op，不访问被锁住的数据库；closed store 仍拒绝操作。
- 批量写入/按 ID 和 filter 删除为事务；写事务使用 IMMEDIATE，读事务为 DEFERRED 一致快照，busy timeout 可配置。每次操作独立打开并关闭 JDBC 连接，无后台连接池；close 等待在途操作并拒绝后续访问，不删除 DB。没有主动启用 WAL 或改动用户 journal 策略。
- 向量使用 little-endian float BLOB；TextSegment 原文及有版本号的 primitive metadata 编码持久化，保留 String/Integer/Long/Float/Double/UUID，不用 Java 对象反序列化。文件使用 schema version 1，未知版本/损坏 payload 报错，不自动改写 schema。非 SQLite 文件拒绝打开且不覆盖。
- 精确余弦检索沿用 core 公式，Java 端执行所有 Metadata Filter，O(N × dimension) 工作量、O(k) 结果队列；按分数降序、相同分数 ID 升序。无 TextSegment 的向量可存储，但不匹配 metadata filter；RAG 使用带文本的入库结果。未实现 SQL 过滤下推、ANN 或向量扩展。
- JDBC 是阻塞 I/O，searchAsync 保留 core 的不支持默认，异步 RAG 通过显式 offloadBlocking(true) 运行在普通线程；Future 取消不能保证中断已开始的 JDBC 扫描，操作结束后释放连接。测试验证 embedding 阶段取消、HTTP 释放，以及错误/超时/完成后的数据库锁释放，不声称具备原生异步数据库 I/O。
- 新增 20 个 Java 8 SQLite 用例和 3 个 SQLite RAG 用例：重开、类型/Unicode、upsert、维度/排序/过滤、事务失败回滚（含更新与删除）、损坏/schema、120 行四写者双实例并发、一致快照、busy/锁/close，以及与内存存储的确定性随机对照。RAG 覆盖真实 loopback embedding/chat HTTP、同步/Future/回调/Publisher 四条路径。另以两个独立 Java 8 进程完成写入退出与只查询的重启消费。
- 本阶段 122 个真实 Java 8、627 个 JDK 17 选定回归、62 个 Publisher TCK 通过，14 项官方 untested 保留；31 JAR/5978 基础类审计、4752 类链接通过。只验证本机外置卷 macOS/aarch64；未验证网络文件系统、同时多进程写、异常进程终止/断电恢复及其他 JNI 平台。Guardrails 继续按用户要求跳过。

## 第七阶段：SQLite 会话记忆（jdk8-m7）

- 同一 `langchain4j-sqlite` 模块新增 `dev.langchain4j.store.memory.chat.sqlite.SqliteChatMemoryStore`，实现 core ChatMemoryStore/AutoCloseable。七模块坐标/BOM 和驱动版本保持不变，无新增生产依赖；共享内部 SqliteDatabase 的逐操作连接、事务与 close 生命周期，原向量数据 schema 未改变。
- 会话 ID 限定非 null String/Integer/Long/UUID，按类型命名空间持久化，不通过任意对象 toString 隐式转换；`"1"`、`1`、`1L` 相互隔离，空字符串有效。不支持其他 Object ID 是新实现的明确限制。
- 每条会话以 core ChatMessageSerializer/Deserializer（含既有 SPI）JSON 保存，单独 schema version 1/payload format 1。updateMessages 整段原子替换，deleteMessages 仅删除对应 ID，缺失会话返回独立可变空列表；读结果修改不影响已存数据。使用默认命名消息类型 codec，无新增 Java 对象反序列化或多态类加载配置。属性语义沿用 JSON codec，不承诺任意自定义对象/数值类型无损。
- 已验证 System/User/Ai/ToolExecutionRequest/ToolExecutionResult/Custom、消息属性及图像 URL 内容的 codec 往返；未知版本、无效 JSON/null 消息显式失败，事务失败不清空旧历史。该验证不代表所有多模态模型调用或自定义 codec 均已验收。
- SQLite 向量和记忆可共用文件，分别使用独立表/配置；清理一个存储不删除另一存储数据。ChatMemory.clear 或 deleteMessages 删除持久化历史，AiServices 的 evictChatMemory 只移除内存实例，后续会从 DB 恢复，二者不能混淆。
- 默认 async 方法保留 core 不支持失败；显式 builder.asyncExecutor(executor) 才把阻塞 JDBC 操作提交到调用方 Executor，不声明原生异步 I/O，store.close 不关闭 Executor。异步写入在提交前固定 JSON 快照；验证排队任务取消、Executor 拒绝及关闭后异常均经 Future 返回。
- 修复 MessageWindowChatMemory messagesAsync 的 thenApply 投影及 addAsync 的组合取消断点；AI Services 的记忆组装链现追踪 read/system/user write，Future/Publisher 取消可传递到存储任务。已验证 I/O 前取消后不会继续把排队的问题写入历史或请求模型。取消不回滚已提交历史，也不保证中断运行中的 JDBC；Guardrails 逻辑保持未改动并继续跳过。
- 事务保护单次完整快照，不覆盖一次模型 turn 或跨方法 read-modify-write；同 memory ID 的并发 AI Service 调用仍需应用串行化以防丢更新。测试验证不同会话隔离和同一 ID 的原子快照替换，没有声称自动合并同会话并发对话。
- 新增 27 个真实 Java 8 用例（15 存储/异步/并发、3 窗口取消、7 HTTP/SSE/会话/工具、2 RAG 共存），上游新增消息 codec/ToolExecutionResult/System/Custom 回归。总计 149 Java 8 / 692 JDK 17 / 62 Publisher TCK 通过，14 项官方 untested 保留；31 JAR/5986 基础类审计、4756 类链接通过，三个既有可选平台排除不变。
- 独立 BOM 的两个 Java 8 进程在同文件保存向量与记忆：writer 经自动工具循环保存 4 条消息，reader 复用工具调用/结果历史向模型发送 5 条并保存 6 条，使用真实 HTTP 适配器和 loopback fixture；真实远程服务、跨平台 JNI、崩溃/网络文件系统等边界沿用第六阶段声明。

## 第八阶段：MCP 基础工具客户端

- 新增兼容模块 `langchain4j-mcp`，复用现有 Jackson 2/OkHttp 版本，没有 JDK HTTP Client 或官方 SNAPSHOT 运行时依赖。仅验收 MCP 2025-11-25 的 initialize/initialized、tools/list 与 tools/call 子集，不声明完整规范或全模块功能支持。
- `StreamableHttpMcpTransport` 改用 OkHttp，支持 JSON/连续 POST SSE、会话头/协议头、鉴权自定义 headers、分页、同步/原生异步工具和四条 AI Services 工具循环。默认协议固定为 2025-11-25；其他协商版本明确失败。
- 新增 builder `httpClient(OkHttpClient)`，调用方拥有其 executor/pool。原 `sslContext`/`executor` builder 方法未保留，改为在 OkHttp builder 配置 TLS（配套 trust manager）和 dispatcher。默认客户端由 transport 关闭；显式客户端只取消该 transport 自己登记的调用。默认 timeout 同时约束 connect/read/call，显式客户端使用自身配置。
- 工具直接 Future 及 McpToolExecutor Future 取消会发送取消通知并关闭 HTTP；超时保留上游模型可见错误文本。初始化失败关闭传输，pending registry 在所有终止路径清理。取消/断开不能保证服务器回滚副作用，不自动重放工具请求。
- AI Services 保持上游 drain 语义：取消停止后续模型轮次，已启动工具执行到完成或工具超时，以保留补偿结果；不会立即取消其 MCP Future。两种取消路径分别验收，未为本轮更改通用工具执行/补偿逻辑。
- stdio/WebSocket/Registry 源码保留但排除出兼容 JAR；GET subsidiary SSE、SSE 恢复、modern 协议/订阅、旧 HTTP+SSE、404 自动重建会话与 DELETE 结束会话未实现。resources/prompts/roots/多模态等仅部分转换回归，业务路径未验收。会话过期后调用方需关闭并重新创建客户端。
- 独立 BOM 的 McpExample 使用 loopback OpenAI -> AI Services -> MCP 工具 -> 模型完整闭环；无需外部服务和凭据。TLS/OAuth 自动授权、代理、真实服务器互操作未验证。详见 `langchain4j-mcp/README-jdk8.md`。
- 协议来源：[MCP 2025-11-25 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)。本阶段范围小于完整规范，未实现条目不计为通过。

第八阶段最终 `./jdk8/build.sh -o`：173 个真实 Java 8、850 个 JDK 17、62 个 Publisher TCK 通过，另列 14 个官方 untested；33 JAR/6278 基础类审计、4902 类 Java 8 链接及独立 BOM MCP 示例通过，无新增可选平台排除。结果见 `verification/milestone-8.json`。

## 第九阶段：MCP 会话与 SSE 恢复

- 八模块及依赖版本不变；会话 404 后共享新握手，旧请求明确失败，后续新请求等待。能力/缓存按代际刷新，原 POST 不自动重发。恢复请求使用负数 JSON-RPC ID，默认客户端请求仍使用非负 ID。
- POST SSE 有已提交游标时通过 GET 恢复；可选 subsidiaryChannel(true) 启用独立 GET SSE 通道，405 正常降级。完整事件提交 Last-Event-ID，不同流/会话隔离，支持 retry、空 id 重置、UTF-8/BOM/CR/LF 和有界重试。损坏协议不作为网络故障重试。
- 新增 timeout 总 deadline（包括握手等待/全部恢复）、reconnectInterval、maxReconnectAttempts、closeTimeout、sessionInitialization/子通道观察 Future、terminateSession 异步释放；close 同步有界等待。请求取消和超时停止 GET/重试并发原会话取消通知。AI Services 保留已启动工具 drain 语义。
- terminateSession/close 幂等，停止本地任务并 DELETE 已知会话；成功/404/405 正常完成，其他错误可从 Future 观察。调用方提供的 HTTP 客户端 executor/pool 不关闭，GET dispatcher 和恢复 scheduler 始终由 transport 关闭，自建客户端还关闭 POST executor/pool。
- 为避免隐式重放，派生 OkHttp 客户端禁用连接重试、重定向和自动认证跟进，POST body 使用 one-shot；followRedirects(true) 现在明确拒绝。显式 headers 提供鉴权，GET/DELETE 的 headers supplier 上下文可为 null。原 HTTP 客户端的自定义 interceptor 仍由调用方负责，不应自行重发工具请求。
- 新增 32 个 Java 8 故障验收；独立 BOM 示例增加 recovery 模式（404 -> 新握手 -> 应用新调用 -> POST SSE 断线/GET 恢复 -> DELETE），验证工具执行仅一次，未重放失效 POST。最终结果记录于 milestone-9 快照；Guardrails 继续跳过。
- 恢复与 DELETE 都不能保证服务器撤销已经执行的副作用，也不能清理尚未返回 ID 的远端分配。真实远程服务器/TLS/OAuth/代理、跨进程持久化游标、modern 和 stdio/WebSocket/Registry 范围不变，未声称完整 MCP 或 exactly-once 执行。

第九阶段最终 `./jdk8/build.sh -o` 通过：205 项真实 Java 8、850 项 JDK 17、62 项 Publisher TCK，14 项官方 untested 单列；33 JAR/6294 基础类审计、4910 类链接和独立 BOM 恢复消费通过。GET 503 Retry-After:0 计数回归复现 6 次请求并修正为配置允许的 3 次。详见 `verification/milestone-9.json`。


## 第十阶段：MCP stdio、资源与提示词（jdk8-m10）

- stdio 纳入兼容产物，保留上游包名和 builder。`Process.onExit/pid` 改为 Java 8 `waitFor` daemon 观察线程；UTF-8 Writer 替换吞掉管道错误的 PrintStream，拒绝跨行消息，串行写入并传播 IOException。命令和附加环境使用不可变快照。
- 显式 start 替换旧进程会先取消其请求并回收进程；旧进程退出回调按身份隔离，不影响新进程。stdout EOF、异常退出或 I/O executor 拒绝会失败待处理请求并清理。初始化取消能传至原请求。
- close 幂等且终止 transport，关闭后不能重新 start；正常终止等待 500ms 后 destroyForcibly，再等待 500ms。与上游仅 destroy、允许旧进程暂留及 close 后再 start 的行为有意不同。这里管理直接启动的 Process，不声称遍历清理任意启动器创建的整棵进程树。
- 调用方或 DefaultExecutorProvider 的 executor 都不由 transport 关闭；自定义 executor 应提供至少两个可用 I/O 槽位用于 stdout/stderr。独立进程等待线程不占用这两个槽位。Java 8 环境变量的非 ASCII 编码受 JVM/系统 locale 约束；UTF-8 JSON 通道另行验收。
- HTTP 与 stdio 验收资源分页/模板、text/blob 读取、提示词参数和消息角色转换、资源订阅更新、日志/进度。HTTP 另覆盖三类列表通知以及模板缓存失效、RPC 错误、超时与中断后的取消/释放。同步资源/提示词中断现在取消未完成请求并保留线程中断状态。
- 三个新回归复现查询期间 list_changed 被随后旧结果覆盖。新增各类列表独立代际，在同一锁内失效缓存和共享更新引用；通知前发出的请求结果仍可交付原调用者，但不再污染通知后的缓存，不承诺服务端分页的一致快照。
- 保留原有 850 项选定上游回归，增加完整 JsonRpcIoHandlerTest/ProcessStderrHandlerTest 两类 8 项。上游 StdioMcpTransportTest 尚未纳入：其中断言重启后旧进程仍存活，与本分支有界强制回收的新生命周期约定不一致；没有改写原始测试，也不把它计为通过。新生命周期由实际 Java 8 子进程验收。
- 独立 BOM McpStdioExample 在 Java 8 父子 JVM 中读取资源/提示词，再通过真实 loopback OpenAI 与 AI Services 完成 stdio 工具循环，退出后验证子进程释放。现有 HTTP 会话/SSE 恢复示例保持运行。
- WebSocket/Registry、其他协议、完整 roots/多模态和真实远端互操作仍待推进；没有新增生产依赖。最终 clean exit 0：238 Java 8、858 JDK 17、62 TCK 通过，14 官方 untested 单列；33 JAR/6302 类审计、4914 类链接和独立 BOM 全部通过。快照见 verification/milestone-10.json。


## 第十一阶段：MCP Registry（jdk8-m11）

- Registry 客户端与全部模型纳入同一 MCP 兼容 JAR；URLEncoder 的 Charset 重载改为 Java 8 UTF-8 名称重载，LocalDateTime 模式匹配转为普通类型检查，移除高版本 Deprecated 元数据。无新增生产依赖。
- 保留固定基线全部六个同步入口：listServers、getServerDetails、getSpecificServerVersion、getAllVersionsOfServer、healthCheck、ping。保留其 v0/v0.1 路径，不将本地模拟验收表述为当前公共 Registry 服务互操作；废弃 getServerDetails 依旧保留。
- 游标、搜索、版本和更新时间分别编码为查询参数，避免 `&/+/=/Unicode` 改变 URL；名称与版本编码为单一路径段，拒绝空或纯点段。请求头从 supplier 获取后复制，不再修改调用方的只读 Map；supplier 每次请求重新调用。
- API 仍为同步阻塞，不提供或宣称原生 async/请求级取消。超时由配置的 HTTP client 控制，错误和超时后可继续请求；响应/错误体由已验证 OkHttp 适配器关闭，调用方 HTTP executor/pool 所有权不改变。默认 SPI 仍选兼容 OkHttp。
- 新增 Java 8 覆盖所有入口、嵌套包/远端/发布方与官方元数据、UTC 时间、游标回传、URL 编码、只读/动态请求头、并发 32 请求、HTTP 错误/损坏 JSON/超时恢复。
- 新纳入三个完整上游 Registry 测试类共 6 项。McpRegistryModelTest 的原始文件扫描要求目录 CodeSource，首轮加载 JAR 报 Not a directory；测试 harness 改为使用同次生产编译的 MCP target/classes，原始测试断言未改且不跳过。JAR 的独立消费、字节码及链接仍单独验收。
- 独立 BOM McpRegistryExample 使用默认 HTTP SPI 在实际 Java 8 验证编码查询、详情版本、元数据、ping/health。只读取本地夹具的发现数据，不自动安装或启动 Registry 提供的命令。
- WebSocket/其他协议及全部后续 Agentic、模型/向量库仍待完成，整体目标保持未完成。最终 clean exit 0：250 Java 8、864 JDK 17、62 TCK 通过，14 官方 untested 单列；33 JAR/6354 基础类审计、4940 类链接及全部独立消费通过。快照见 verification/milestone-11.json。


## 第十二阶段：MCP WebSocket（jdk8-m12）

- WebSocket 生产源码纳入 MCP 兼容 JAR，使用现有 OkHttp 4.12.0 替换 JDK HTTP/WebSocket，无新增生产依赖。保留传输类名和请求/消息桥接；公开 WebSocketMcpListener 改为 OkHttp WebSocketListener，原 JDK WebSocket.Listener 签名不能保留在 Java 8。
- builder 的 executor 改为通过 httpClient(OkHttpClient) 配置 dispatcher；原 sslContext(context)/reloadSslContext(context) 改为显式提供匹配 X509TrustManager 的双参数版本，或配置/替换完整 OkHttp 客户端。reloadHttpClient 和 reloadSslContext 只影响后续连接，不打断当前通道。调用方的客户端 executor/pool 不被关闭；自建客户端和 deadline scheduler 由 transport 释放。
- 一代连接共享一次升级和初始化；断开时清空客户端能力/缓存，失败全部旧在途请求，只有后续新调用才建立共享新连接并重新握手。恢复初始化使用独立负数 ID，取消初始化观察 Future 或一个等待调用不影响其他恢复等待者。不自动重发任何原工具消息；服务端副作用仍可能已发生。
- timeout 限制升级与 MCP 握手的总时间，requestTimeout 限制单请求（包括等待恢复），closeTimeout 有界等待关闭握手后 cancel。默认分别为 60s/60s/1s。close 幂等且最终关闭，包含未完成升级；远端断开/二进制消息失败旧请求。单条损坏 JSON 沿用上游读取策略，记录后继续。
- maxPendingRequests 默认 1024；maxQueuedBytes 默认 1MiB，最大 16MiB。发送前检查 UTF-8 字节与现有队列，超限请求明确拒绝，不静默丢弃或重试；重复在途 ID 拒绝且不替换旧 Future。取消/超时只通知仍属于原连接且已发送的请求，服务器取消不回发取消通知。
- OkHttp 重组分片后在串行 socket reader 上交付完整消息，回调返回后继续读取，没有额外无界应用消息队列。调用方通知/完成回调应快速返回，需要阻塞工作时显式 offload；不能在共享读取回调中同步等待同一连接。出站/在途限制不等于对服务端任意巨大入站帧做预分配前内存上限保护。
- 真实 Java 8 网络验收包含分片 UTF-8/交错 ping、错误/取消/超时、关闭/二进制/断连、20 在途失败、20 后续调用共享恢复、握手失败/恢复失败后新调用重试、缓存通知、服务器 ping/roots、WSS 和 TLS 热更新、资源所有权及有界队列。WSS 使用仅用于 loopback 的合成 JKS 夹具，口令 synthetic-fixture，证书 2020–2040；不读取宿主服务凭据。
- 既有 864 项 JDK 17 选定回归保持运行。原始 WebSocketMcpTransportTest 依赖 JDK WebSocket 和被替换的私有字段/单参数 SSL API，未纳入且不计通过；Java 8 网络验收覆盖替代实现的发送失败/成功、自定义 TLS 与热更新。
- 独立 BOM McpWebSocketExample 验证第一次工具帧断连明确失败，应用新发起 AI Services 调用，在第二连接握手后经 OpenAI HTTP + WebSocket 工具返回 7 yuan（分片响应）；失败工具 1、实际执行 1、正常关闭 1，无自动重放。
- 当前固定 2025-11-25，其他协议、完整 MCP 能力及后续全部模块仍待推进；不声明标准化 WebSocket MCP 规范或外部服务器互操作全部通过。最终 clean exit 0：276 Java 8、864 JDK 17、62 TCK 通过，14 官方 untested 单列；33 JAR/6364 类审计、4945 类链接及全部 BOM 消费通过，见 verification/milestone-12.json。

依据：[OkHttp 4.12 WebSocket API](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/WebSocket.kt)、[串行消息回调接口](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/WebSocketListener.kt)、[RFC 6455](https://www.rfc-editor.org/rfc/rfc6455)。


## 第十三阶段：固定基线现代 HTTP 协议（jdk8-m13）

- 接通固定源码基线中的版本标识 `2026-07-28`：server/discover、无旧会话 ID 的 HTTP 请求、Mcp-Method/Mcp-Name/Mcp-Param 请求头，以及 subscriptions/listen。此处描述固定基线代码合同，不据此宣称当前公共规范或真实服务器互操作已验证。默认仍是 2025-11-25；显式 protocolVersion("") 才触发自动探测。
- 现代响应中的 MCP-Session-Id 不采用，404 明确失败，不进入旧 initialize/DELETE 恢复；取消通过关闭对应 HTTP 流，不另发取消 POST。旧协议的会话/GET 游标恢复保持原样。现代订阅 EOF 明确失败，应用可新建订阅；没有自动重放原订阅 POST 或使用旧会话 GET 恢复。
- reserved 请求头由 transport 最后生成，清除供应者陈旧的 session/version/method/name/param/cursor 值；名称/URI 和 schema 声明的参数使用既有 McpHeaderEncoding。保留 base URL、显式鉴权、自定义 HTTP 客户端及所有权约定。
- 新增 maxConcurrentStreams（默认 64），GET/长期订阅 POST 使用独立 dispatcher，避免默认每主机 5 个连接槽位被长流占满。请求 deadline 覆盖排队和等待确认；只有同流、同订阅 ID 的 acknowledged 才取消该 deadline，确认后的生命期由订阅取消/断线/关闭控制，底层 read timeout 仍有效。
- HTTP JSON/SSE 结果必须匹配该 HTTP 请求 ID，订阅确认必须匹配其 HTTP 流，拒绝用一个流的结果/确认完成其他并发操作。协议切换拒绝真实活动请求，已完成但清理回调未退栈的请求不再被误判 busy；同版本设置幂等。
- 高层现代异步工具补齐 input_required + requestState 续传：服务器明确给出状态才新建下一请求 ID，保留参数头和调用上下文，受工具整体时限及 multiRoundTripMaxRetries 限制。网络故障/HTTP 错误不续发；取消/超时到达当前实际请求，不错误地取消已完成的第一轮。交互 inputRequests 仍沿用基线明确拒绝，未伪造自动回答。
- 测试复现并修复等待订阅确认时中断后遗留 SSE；同步工具和同步请求状态续传的中断也释放当前 I/O，并保留中断位。现代/旧版本回退覆盖方法不存在、发现超时和 supported 列表选择 2024-11-05；这里只验证固定基线单端点引擎的旧版本字段，原始 2024 双端点 HTTP+SSE 未据此验收。
- 独立 BOM McpModernExample 在真实 Java 8 验证发现 1、异步续传帧 2、实际工具执行 1、订阅确认与释放、取消 POST 0，经 OpenAI HTTP + AI Services 返回 7 yuan。固定基线较旧默认入口与此前全部回归/示例保持运行。
- 现代 stdio/WebSocket 的专项、更多剩余 MCP 能力和后续全部 Agentic/模型/向量库仍待推进；本阶段不是完整 MCP 完成声明。最终 clean exit 0：307 Java 8、864 上游、62 TCK 通过；14 官方 untested，33 JAR/6366 类审计、4946 类链接和全部独立消费通过，见 verification/milestone-13.json。

旧 HTTP/SSE 恢复和每请求响应的依据仍见 [MCP 2025-11-25 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)；现代字段以本地固定基线 `git show 7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79:langchain4j-mcp/src/main/java/dev/langchain4j/mcp/client/DefaultMcpClient.java` 及同基线 HTTP transport 为审计来源。


## 第十四阶段：现代 stdio/WebSocket（jdk8-m14）

- WebSocket 以同一连接代际引擎支持固定基线 2026-07-28 的 server/discover；现代模式不发送旧 initialize/initialized，恢复只重新发现能力并为新调用开放通道，不重放丢失工具。旧 2025-11-25 和 2024-11-05 的 initialize 路径继续验证。
- 初次发现的 RPC 错误交回高层协商/旧版本回退；恢复握手失败会失败等待者，后续应用新请求可再次建立连接。现代发现结果按发现模型转换更新 instructions/能力，自动列表订阅在恢复后重新建立。首先由健康探测恢复连接时同样刷新，不让它绕过能力更新。
- WebSocket subscriptions/listen 在同 ID acknowledged 前受 requestTimeout 约束，确认后取消该 unary deadline，直到结束、取消、失联或关闭。它仍计入 maxPendingRequests。共享通道取消由 transport 发送一次，保留现代 _meta；高层订阅放弃/取消不再重复发送。stdio 由高层发送一次取消通知，关闭时终止子进程。
- 现代异步工具区分调用方取消与服务器 CancellationException，避免服务器取消导致 stdio 回显取消。自动探测成功后保存协商的现代版本，后续请求/通知/健康探测不再将配置中的空字符串写入 _meta。
- stdio 在进程丢失、替换和重新启动时失效客户端列表缓存，修复实际子进程重启后沿用旧工具列表；高层健康检查负责重新启动/发现并刷新能力。调用方需明确启用/配置自动健康检查；不将原工具请求交给新进程重放。
- 新增同一组 stdio 实际 Java 8 子进程 / 实际 WebSocket 双通道验收：发现、UTF-8 工具、资源/提示词、确认/拒绝/超时/中断、长期列表订阅、取消去重、状态续传/取消/时限、旧版本和能力/缓存恢复；另有 WebSocket 20 等待者共享恢复、旧订阅隔离、失败恢复/关闭/健康探测恢复。
- 独立 BOM McpModernExample 增加 stdio/websocket 两模式，验证实际 Java 8 Future AI Services、明确 requestState 续传、工具仅执行一次、订阅取消一次、无坏元数据且通道/进程释放。HTTP 模式与所有旧示例保持不变。
- 本轮单方法过滤参数化测试曾选中 0 个用例，不计验证；最终必须以完整类的 34 个参数化用例和 5 个 WebSocket 恢复用例及全部旧回归为准。原始双端点 HTTP+SSE、其余未验收 MCP 行为和所有后续模块仍不计完成。最终 clean exit 0：346 Java 8、864 上游、62 TCK 通过，14 官方 untested；33 JAR/6366 类审计、4946 类链接及全部独立消费通过，见 verification/milestone-14.json。


## 第十五阶段主 Agentic 基础检查点（WIP，无新阶段标签）

- `agentic-wip` 为显式构建入口；主模块全部生产源码使用 release 8，兼容坐标沿用 `dev.langchain4j.jdk8:langchain4j-agentic:1.23.0-jdk8-SNAPSHOT`。尚未加入 BOM/独立消费，不声明完整支持。
- 25 个 record 改为 final POJO；保留规范构造器、组件访问器、浅值 equals/hashCode/toString，以及生成值方法的 final 属性。增加显式 Jackson 2 JSON 构造元数据，保留已有组件注解；Java 17 record 反射身份和 Record 父类不再存在。
- 模式绑定在方法/集合读取处保留一次读取的局部快照；switch 分支和文本块分别转换为 Java 8 语法及等价字符串。Stream.toList 和集合工厂保持不可变结果。var 先在固定基线副本上以 javac 17 解析，类型归属错误为零，再转换；没有重跑已移植 core/main 的迁移工具。
- 原私有接口辅助方法移入包内 AgentInvokerSupport，不新增公开 default 覆盖点。非静态 PlannerLoop 中两个静态辅助方法改为实例私有方法。ServiceLoader 改为 Java 8 迭代发现，保留提供者初始化顺序与注册表合并。
- Optional.ifPresentOrElse、Predicate.not、Objects.requireNonNullElse、Class.getPackageName、Files.writeString 使用 Java 8 等价实现。报告默认 UTF-8、覆盖写入和损坏代理项报错；记录 Record 自动值方法 final 属性的首次 API 审计失败及修正，不将失败计作通过。
- 14 项真实 Java 8 专项包含基础模型 Agent、顺序转换/状态传递、条件、循环上限/退出、并行实际重叠及调用方 executor 所有权、错误恢复、缺参不执行、消息变换、空白/null 状态、完成与 deferred 状态 JSON 恢复，以及报告写入。模型为进程内 stub，不宣称远端互操作；该集合不能代替完整 Agentic 验收。
- 主模块完整功能、选定上游回归、HITL/挂起恢复、planner/supervisor、声明式/工具/补偿/记忆/可观测性完整场景，以及其余三个 Agentic 模块仍待推进。验证和 API/依赖细节见 AGENTIC-PORT.md 与 verification/agentic-main-wip.json。
