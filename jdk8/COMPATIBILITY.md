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


### Agentic 第二个 WIP 单元：回归与恢复

新增全部 51 个固定基线 `*Test.java` 的 JDK 17 harness，原始断言不变。仅复制的共享 StreamingChatModelMock 将两个 Flow 导入替换为 Reactive Streams；InvoiceFixtureTest 引用的三个嵌套声明从 IT 精确抽取，避免加载模型配置。`audit-agentic-tests.sh` 校验覆盖清单、字节级未修改测试及夹具差异。

实际 Java 8 增加 supervisor/HITL 挂起期间不继续规划，以及两个独立 JVM 的文件检查点恢复、响应去重/Unicode 和已完成步骤不重放。该 store 为单写者测试夹具，挂起后正常退出，不声明断电、强杀或生产存储保证；完整 Agentic 仍 WIP。


本 WIP 单元最终 clean exit 0：Java 8 362 项（含 Agentic 16），JDK 17 1115 项（含全部 51 个 Agentic 单元类共 251 项），TCK 62 通过/14 官方 untested；1539 通过。原始测试/夹具来源、API 对照和 208 类附加产物/5154 类链接均通过；BOM 支持仍只有此前八模块。快照见 verification/agentic-recovery-wip.json。


### Agentic 第三个 WIP 单元：异步中间 SSE 与网络验收

AgentExecutor 的 async 分支此前返回未消费 TokenStream，真实 HTTP/SSE 测试中的下一文本 Agent 发生 ClassCastException。当前在派发时捕获 streaming 决策，并在异步 worker 内消费中间 SSE 为文本；仅末端流保留 TokenStream 透传。避免根据异步完成时已前进的 planner 状态重新决定透传。公开 API、同步分支、不可变集合及依赖坐标不变。

新增 18 项实际 Java 8 loopback 网络场景：HTTP/SSE、async 交接与确定性 planner 竞争、末端惰性/UTF-8 分段/完成唯一性、显式取消及错误/超时释放、四路径工具循环、结构化 POJO 和显式 @MemoryId 会话隔离/清理。无外部模型调用，OpenAI/OkHttp 仅增加 test 依赖；scope eviction 与 chat memory eviction 仍为独立 API。完整 Agentic/BOM 消费继续 WIP。


本次 HTTP/SSE WIP 最终 clean exit 0：实际 Java 8 380 项（Agentic 34），JDK 17 1115 项，TCK 62 通过、14 官方 untested，共 1557 通过。依赖树不变，API/来源/208 类产物/5154 类链接和既有 BOM 消费均通过；结果见 verification/agentic-http-wip.json。中间流全局取消、完整恢复/补偿与异步持久化等仍待验收，不据此标记完整支持。


### Agentic 第四个 WIP 单元：延迟错误恢复与补偿

真实 Java 8 用例复现中间 SSE 的 error handler 被绕过、异步 HTTP 重试重复记录的问题。AgentExecutor 现在将中间流失败作为 AgentInvocationException 交给 scope 恢复策略，延迟操作的 RETRY 只执行新尝试、不重新发布操作或通知 planner。普通输入键在重试前重新解析。显式 retry() 可再次发送请求；默认异常传播不发起重试，不据此承诺副作用恰好一次。

中间同步流使用私有 RecoverableStream，恢复/重试 offload 到已有默认 executor，避免占用 HTTP 读取线程；结果、恢复异常及回调终止通过同一个 Future 交付。异步 Agent 的错误恢复同样在已发布的 Future 内完成。末端流仍按原 TokenStream 回调/取消 API 交给调用方。公开 API 与生产依赖不变。

新增 14 个真实 Java 8 网络场景，包括回退/重试与历史唯一性、单 HTTP reader、处理器失败、输入更新，以及四路径工具补偿、逆序补偿和恢复成功不补偿。部分复杂恢复/补偿、同键输入输出/自引用、异步持久化与挂起组合等仍待验收，Agentic 整体继续 WIP。


第四个 WIP 单元最终 clean 验证：Java 8 394 项、JDK 17 1115 项、TCK 62 通过/14 官方 untested；1571 通过。API 和原始测试来源审计、无变化的生产树、209 类附加产物及 5155 类 Java 8 链接、既有 BOM 示例均通过。结果见 verification/agentic-error-recovery-wip.json；Agentic 整体及 BOM 消费仍未完成。


### Agentic 第五个 WIP 单元：异步同键状态与重入

新增内部 DeferredScopeContext/Frame：派发异步操作及中间流恢复时保留自身 output key 的输入，调用内 scope 读写不等待自身 pending，也不向外提前发布中间值；绑定在 finally 恢复/释放。其他键和原始 state() Map 行为保持原样，未引入全量状态事务。新增类型带 @Internal，原有公开/受保护签名保留。

readState 可展开嵌套延迟值；完成旧读取或 root drain 时按 pending 对象身份进行条件回填，保留更新的赋值。scope 读取未完成 SuspendedResponse 发出受控挂起；直接 blockingGet API 仍按其文档阻塞。真实 Java 8 子进程用例验证同键 HTTP/SSE 重试、恢复更新输入、自引用 async scope function 与中间写、async HITL+同步消费者、过期回填和线程池上下文清理。其余复杂组合、延迟持久化、末端挂起和全局取消继续 WIP。


第五单元最终 clean 验证：Java 8 404 项、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1581 通过。原 API/来源审计及生产树保持，新增内部工具类型明确记录；211 类附加产物和 5157 类 Java 8 链接、既有 BOM 示例通过，详见 verification/agentic-deferred-state-wip.json。Agentic 与完整长期目标继续 WIP。


### Agentic 第六个 WIP 单元：末端挂起检查点

已完成 planner 的待人工响应保存为执行状态中的 __terminalAwaiting/可选 __terminalResult；响应完成后在终点返回结果，而非重新执行最后的提供者。重复等待保持挂起。这是兼容分支私有检查点扩展，未承诺原始上游可直接读取。

新增 @Internal public DefaultAgenticScope.resolveAsyncStates() 根 planner 收尾辅助方法，展开 async 外层计算而不等待人工响应本身。原有签名及 AgenticScope 接口不变，API 对照会明确报告这个内部新增方法。非 AI wrappers 和延迟恢复不再包装受控挂起为普通失败；根执行器跳过错误补偿，成功求值输出之后才清理执行状态。

新增八项真实 Java 8 用例，含末端同步/异步/重复等待、异常式挂起、async 消费者恢复、HTTP 工具不补偿/不重放，以及跨 JVM 同步 HITL 文件检查点。未完成的消费者会从方法入口再执行；任意 Java 代码行续跑、所有并行/嵌套 async 组合与延迟持久化不在本单元通过声明内，完整 Agentic 继续 WIP。


第六单元最终 clean 验证：Java 8 412 项、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1589 通过。API 审计保留全部原成员并报告一个内部新增方法；来源、生产依赖、211 类产物/5157 类链接和既有 BOM 示例通过。快照见 verification/agentic-terminal-suspension-wip.json，完整 Agentic/长期目标继续 WIP。


### Agentic 第七个 WIP 单元：并行恢复与 supervisor 规划

原始 AgenticScope 接口和已有 API 保留；DefaultAgenticScope 新增公开 @Internal suspendedAsyncAgentIds()/resolveAsyncState(AgentInvocation)，与已有 resolveAsyncStates 合计三项内部方法新增。AgentInvocation.rawOutput 为包内方法。未完成 async 消费者按最新调用结果从 planner 完成集合移除，只在人工输入完成后从方法入口重入；已完成副作用不重放。

supervisor 先等待选定异步结果，人工输入未完成时不规划；私有执行状态扩展 awaitingOutputKey/lastResponse，将实际回答带入恢复后的模型请求，executionState 仍返回不可修改 Map。两个并行人工输入的部分完成、重复等待及异步 query 的下一轮规划均由有界 Java 8 子进程验证；不扩大为任意调度、跨进程 async 持久化或全局取消完成声明。

第七单元完整 clean exit 0：真实 Java 8 418 项（支持模块 346 + Agentic 72）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1595 通过。原 API 成员与测试来源保留，显式记录三个内部新增方法；生产依赖不变，211 类附加产物与 5157 类链接及既有独立 BOM 示例通过。快照见 verification/agentic-async-planning-wip.json；完整 Agentic 与长期目标继续 WIP。


### Agentic 第八个 WIP 单元：异步持久化

serializableCopy 对顶层状态/调用输出展开已完成 async/deferred 值；运行中或受控挂起的 async 不序列化 Future。历史以 null 输出保留调用身份，私有 JSON 字段 incompleteAsyncAgentIds 记录未完成方法，恢复时参与已有 planner 重入分类。字段在登记新调用后清理，二次快照保持未完成状态；不改变活跃 scope 的原始输出。快照不等待计算，避免 checkpoint 写锁与 worker 读锁互等。原 API 及生产依赖不变。

根流程返回挂起前再次持久化已稳定的人工作答标记与 planner 状态。新格式是兼容分支检查点扩展，不承诺未改造上游直接恢复。三个实际 Java 8 新用例验证 async 人工响应、受控挂起消费者跨进程恢复及运行中非阻塞快照。任意崩溃时间点、任意 planner 在途恢复、外部副作用恰好一次、多 writer 原子性、普通 async 失败历史与用户嵌套容器 Future 仍待验收。

第八单元完整 clean exit 0：真实 Java 8 421 项（支持模块 346 + Agentic 75）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1598 通过。原基线 API 与三个已记录内部增项保持，测试来源及生产树不变；211 类附加产物、5157 类实际 Java 8 链接及既有 BOM 消费通过。最新快照 verification/agentic-async-persistence-wip.json；完整 Agentic 与长期目标仍未完成。


### Agentic patterns 基础检查点（WIP）

patterns 全部 15 个基线生产源编译为 release 8；四个 record 改 final 值类（公开 Desire 一个），保留组件访问器/校验/值方法及浅引用合同。Desire 工厂的 List 不可修改，直接构造继续保留调用方 List；显式 JSON 构造元数据不改变公开签名。集合工厂/Stream.toList/模式绑定/文本块降级，P2P 提取器 annotation 常量与基线逐字节相等。原 16 可见类型的 API 对照无遗漏/改变或新增成员。

通用终点等待不再冻结非空 planner 结果而绕过 planner 恢复；Decision Router 可在恢复后按 deferred 列表读取人工实际回答。null-result 的内置终点检查点及方法级恢复合同保持。该变化由两个未改动的原始 router 恢复断言、真实 Java 8 router 用例和既有主 Agentic 回归验证。

Patterns 基础检查点完整 clean exit 0：真实 Java 8 430 项（支持模块 346 + 主 Agentic 75 + patterns 9）、JDK 17 1185 项（含 patterns 原始 70 项）、TCK 62 通过/14 官方 untested，共 1677 通过。main/patterns API 与原始测试来源审计通过；patterns 22 类均 classfile 52，无禁止引用，扩展链接 5179 类及既有 BOM 消费通过。快照 verification/agentic-patterns-foundation-wip.json；仍属 WIP，无新阶段标签或 BOM 支持声明。

全部 policies、模型路由/提取、async/streaming/HITL/取消与资源释放、声明式组合及独立消费仍需后续验收。当前两个 Agentic WIP 模块不进入支持 BOM，其他两个 Agentic 模块和长期范围不缩减。


### Patterns 异步/流式与错误边界（WIP）

兼容新增类型 AgenticJdk8 增加公开内部 invocationOutput(AgenticScope,AgentInvocation) 方法，等待选定 async 计算与中间流；原基线公开/受保护签名不变。外部 DeferredResponse 标记仍留给 planner 的挂起策略。Voting、Debate position/judge 和 Decision Router 使用实际值，避免占位字符串参与聚合/上下文。

AgentExecutor 私有 PlannerInvocationFailure 只用于跨过当前 Agent 的恢复 catch，在同一执行边界还原原 AgentInvocationException；不新增对外异常类型。planner 读取已失败计算时不再次触发当前 Agent 的恢复处理。非 AI 调用登记不再为了检查聊天上下文而提前求输出。新用例验证一次恢复与原始错误原因链；人工输入/所有补偿和取消组合仍未宣称完成。

Patterns 异步检查点完整 clean exit 0：真实 Java 8 437 项（支持模块 346 + 主 Agentic 76 + patterns 15）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1684 通过。原基线 API/来源和生产依赖不变；新增兼容辅助方法单列，main 212 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-patterns-async-wip.json，仍为 WIP，无新阶段标签或 BOM 支持声明。


### Voting 人工输入/检查点（WIP）

Voting 新增 executionState/restoreExecutionState 的类实现，覆盖原 Planner 默认方法，既有调用签名保留。内部状态 completed 保存含 skipped 的完成 Agent ID，votes 保存按完成顺序的非 skipped 票；顶层 Map 不可修改，集合取快照。恢复后仅派发缺失票，受控挂起消费者未完成而可重入。

持久化 marker 与 scope 响应可能是不同实例，聚合以 voter.outputKey 读取当前 scope 值，未完成 SuspendedResponse 继续受控挂起；不改变 PendingResponse 的阻塞约定。完成票与条件为 false 的 skipped voter 不重放，全部值就绪才调用 strategy。completed/votes 是兼容分支内部检查点字段，不承诺未经改造上游读取。

API 比较器将继承的 dispatch 项规范为可见 owner，并允许接口 default 被类方法覆盖；声明项仍报告两个覆盖新增，所有参数/返回/泛型/throws 和其他修饰保留检查。新增兼容覆盖与两种签名破坏夹具分别通过/拒绝，范围见审计快照。

Voting 恢复检查点完整 clean exit 0：真实 Java 8 444 项（支持模块 346 + 主 Agentic 76 + patterns 22）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1691 通过。原基线 API 保留，两个 Voting 检查点覆盖声明显式单列；比较器兼容/反向验证和来源/依赖审计通过；main 212 类/patterns 22 类均 classfile 52，扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-voting-recovery-wip.json；完整工程仍 WIP，不新增阶段标签或 Agentic BOM 支持声明。

三个跨 JVM 场景均为单 writer 文件夹具、String memory ID 和正常挂起退出，不证明任意强杀时间点、多 writer 原子性或外部副作用恰好一次；Debate 和其他未完成能力继续推进。


### Debate 轮次/人工输入/judge 检查点（WIP）

既有 executionState/restoreExecutionState 签名保留，内部增加 completedInRound/judgePhase/judgeCompleted/可选 judgeOutput，并实际恢复 lastDebatersMessages。上下文仅在新流程初始化时清空，位置全部可用后才推进轮次或 judge；skipped 辩论者保留上一轮冻结位置。

兼容辅助类 AgenticJdk8 新增公开内部常量 PLANNER_MANAGES_RESUME（__plannerManagesResume）。Debate 执行状态带该 true 标记，明确由自身恢复完整 phase/dispatch 集合；通用完成过滤不再误删下一轮的同一 Agent。未声明标记的 planner 继续使用原规则，Planner 接口没有新方法。此标记及新状态字段是本分支内部检查点协议，不承诺未经改造上游或旧 WIP 不完整检查点可直接恢复。

人工 marker 通过 output key 从当前 scope 读取实际响应；PendingResponse 保持阻塞合同。跨 JVM 用例为单 writer/String ID、正常挂起退出，验证不重跑第一轮/已完成辩论者或重复询问 judge；不证明任意强杀点、多 writer 原子性及外部副作用恰好一次。

Debate 恢复检查点完整 clean exit 0：真实 Java 8 451 项（支持模块 346 + 主 Agentic 76 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1698 通过。原基线 API/来源和生产依赖保持；新增内部恢复标记常量单列，main 212 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5180 类与既有 BOM 消费通过。快照 verification/agentic-debate-recovery-wip.json；完整工程仍 WIP，不新增阶段标签或 Agentic BOM 支持声明。


### 失败专用收尾（WIP）

新增 @Internal DefaultAgenticScope.rootCallFailed(AgenticScopeRegistry,AgentListener,Throwable)，原有签名和成功 rootCallEnded 等待合同不变。失败仅展开已完成 DelayedResponse，未回答标记保持未完成，未完成计算不等待；尝试 ephemeral eviction 或持久化观察状态，并清理补偿登记。

兼容辅助类型 AgenticJdk8 新增 suppressCleanupFailure(Throwable,Runnable)，收尾步骤逃逸的错误不替换原始操作异常，而以 suppressed 保留；同一对象不重复附加，也不自我 suppressed。原普通 listener/补偿动作的日志处理约定不改动。失败后返回错误不代表后台任务已取消，后者仍待专项实现；同步用户 SPI 的自定义阻塞行为没有变成带超时的异步接口。

失败收尾检查点完整 clean exit 0：真实 Java 8 459 项（支持模块 346 + 主 Agentic 84 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1706 通过。原基线 API 保留，新增 rootCallFailed 内部方法明确列为第四个 DefaultAgenticScope 增项；来源/生产依赖审计保持，main 212 类/patterns 22 类均 classfile 52，扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-failure-cleanup-wip.json；完整工程仍 WIP，无新阶段标签或 Agentic BOM 支持声明。

新用例验证失败后保存未回答 marker 与 gated 计算的先后关系，不承诺失败后通用自动重试/完整错误历史持久化或全局网络资源取消。


### Root 失败后的 async 任务归属与中断（WIP，2026-10-21）

- 新增五项实际 Java 8 子进程验收先全部失败：错误返回后后台仍运行、输出键覆盖丢失追踪、忽略中断的任务继续写状态、跨 memoryId 隔离，以及等待外部 PendingResponse 的任务不能中断。
- 每个 root 调用维护内部任务登记，不依赖当前 state 的输出键；AsyncResponse 在提交前登记取消动作，失败 root 在补偿之前取消自己的登记任务并中断执行线程。完成后解除登记；线程引用在归还执行器前清除，避免误中断后续工作。
- DeferredScopeContext 捕获调用归属并在 finally 恢复 ThreadLocal；取消与 scope 写入共享边界。已取消任务调用 writeState/writeStateIfAbsent/writeStates、发布解析结果或登记调用时抛 CancellationException，外部调用者仍可检查/更新 scope。取消不进入 Agent 错误恢复。
- owned 任务读取外部 DeferredResponse 时使用既有可中断等待，不取消或伪造外部 marker 的回答；取消后的 AsyncResponse 检查点按未完成处理，不持久化运行时 Future。
- 原失败收尾 inflight 用例从夹具释放后完成改为要求 owned future 已取消；继续保留原始错误、仅一次恢复和人工 marker 未回答的断言。
- 内部 API 增项：DefaultAgenticScope.invocationTasks()/cancelPendingTasks(Throwable)、AsyncResponse.isCancelled()，以及新增 InvocationTasks/Registration/Binding 辅助类型；包名及基线签名保持。无新增生产依赖。
- 验证：完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0。真实 Java 8 464 项（支持模块 346 + 主 Agentic 89 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1711 通过。生产依赖树未变，main 216 类/patterns 22 类均 classfile 52，无禁止引用；main 扩展链接 5162 类、main+patterns 5184 类及既有独立 BOM 消费通过。基线 API/测试来源审计通过，主模块基线可见类型增项累计七个方法。快照 `verification/agentic-cancellation-wip.json`；无新完整阶段标签或 Agentic BOM 支持声明。
- 边界：Java 无法强制终止忽略中断的用户代码，直接修改 state() 原始 Map 或外部副作用不受 scope 方法守卫覆盖。网络流取消、同步并行派发失败收尾、成功调用被覆盖任务的完整收尾、失败历史与自动重试仍待完成；不改变全部 Agentic/MCP/模型/向量库与最终交付的长期范围。


### 同步并行派发失败收尾（WIP，2026-10-21）

- 三项实际 Java 8 子进程先全部失败：默认/自定义执行器等待六秒慢任务并丢失先发生的失败，第二次派发拒绝后首个任务不被中断。
- 同步并行派发也注册到当前 root 的 InvocationTasks。全部正常完成仍等待全部结果；任一任务失败即唤醒 root，根调用按既有失败边界取消其他 owned 任务，保留首次观察到的错误。拒绝提交仍抛原始拒绝异常，已提交任务由 root 收尾取消。
- 继续使用用户提供的 Executor，不关闭调用者执行器；取消后的同步 Agent 异常不再进入恢复处理。直接 inline 执行的 Executor 仍按调用者的同步调度合同执行，不能声称在 executor.execute 返回之前抢占它。
- 验证：完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0。真实 Java 8 467 项（支持模块 346 + 主 Agentic 92 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1714 通过。API/测试来源审计保持，main 216 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5184 类与全部既有独立 BOM 消费通过，无生产 POM/依赖变化。快照 `verification/agentic-parallel-failure-wip.json`；仍 WIP，无新阶段标签。网络流取消、成功调用被覆盖任务收尾、完整失败历史/自动重试以及所有其余长期范围保持 pending。


### 中间流与 OkHttp 调用取消（WIP，2026-10-21）

- 改造前五个真实 Java 8 socket 子进程均显示 root 失败后连接未关闭，覆盖同步/异步中间流在响应头前/首段之后，以及异步普通对话在响应头前。
- core 新增内部 CancellationContext，将资源登记者显式绑定到调用及 OkHttp callback，finally 恢复原上下文；不向无关 executor 任务隐式传播。OkHttp 同步/异步/SSE 请求在执行或 enqueue 之前登记 Call.cancel，终止后解除登记；callback 内的默认工具续传仍沿用原 owner，不关闭共享客户端/执行器。
- StreamingResponse 和框架 RecoverableStream 登记 future 取消，忽略取消后的终止/恢复结果；OwnedResponse 区分框架任务与外部回答，取消值在失败收尾中不替换原错误，检查点不持久化 future。
- 七个网络场景以 server socket EOF、dispatcher running/queued=0、结果已终止且抛 CancellationException、仅一次错误恢复为证据。额外验证直接 HTTP executeAsync 以及 SSE 工具结果触发第二请求后的取消，默认工具只执行一次。夹具清理 cancelAll 在断言之后，不将夹具关闭当作成功证据。
- 首轮全量回归暴露双人工 marker 偶发缺失，新增确定性实际 Java 8 用例在旧逻辑失败：先结算到受控挂起就退出。现先结算其余 async provider，再返回受控挂起；普通失败立即抛出，不直接等待外部 marker 的回答。
- 三项支持模块 Java 8 用例验证无 owner 不取消资源、嵌套/异常恢复和 executor 回调的显式捕获/线程复用无泄漏。生产不新增外部依赖；StreamingResponse.isCancelled() 为内部 API 增项，AsyncResponse/StreamingResponse 保留基线直接 DelayedResponse 接口声明，由内部 OwnedResponse 辅助判断取消状态；不放宽接口审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：实际 Java 8 478 项（支持模块 349 + 主 Agentic 100 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1725 通过。33 JAR/6372 基础类条目审计，默认链接 4949 类；main 217 类/patterns 22 类均 classfile 52，扩展链接 5188 类及全部既有 BOM 消费通过。严格基线 API 和测试来源审计通过，主模块增项累计八个内部方法，无新增生产依赖。快照 `verification/agentic-network-cancellation-wip.json`；仍 WIP，无新完整阶段标签或 Agentic BOM 支持声明。
- 边界：本单元覆盖默认 OkHttp 与框架消费的中间流；terminal TokenStream、任意自定义模型/传输、HTTP 回调的 scope 写入归属和异步工具执行器的取消/上下文传播仍需验收。资源 registrar 不等于恢复全部 Agentic ThreadLocal。成功调用被覆盖任务收尾、完整失败历史/自动重试、scope guard 与用户同步回调的阻塞边界，以及全部其余 Agentic/MCP/模型/向量库范围继续 pending。


### HTTP 回调的 scope 代际归属（WIP，2026-10-22）

- 三个定向实际 Java 8 用例先复现失败：捕获回调取消后仍可写入、嵌套回调未恢复 owner、旧回调无法隔离新的 root 代际。SSE 工具夹具修正为先半关闭服务端输出触发协议 EOF 后，明确复现三种迟到写入全部未被拒绝；夹具早期等待超时不计为有效证据。
- CancellationContext.Registrar 新增内部默认 inContext(Supplier) 钩子，保留单抽象方法合同；InvocationTasks 用私有 registrar bridge 把捕获的代际绑定到 scope 守卫。嵌套及异常时分别恢复之前的 scope/resource 上下文，无隐式线程继承。
- 回调绑定本身不提前拒绝取消：HTTP onFailure/onResponse 仍可关闭响应、解除登记并传递失败。调用者真正写 scope 或开始新工作时，原有 check/mutate 根据捕获代际拒绝；不会错误采用 scope 新调用的 owner。
- 四项定向验收覆盖三种写入、不同 scope 嵌套、线程复用无残留、同一 scope 新旧代际，以及 callback Error 保留和双绑定恢复。新增真实 SSE 工具回调用例在 root 返回原始失败后显式释放被门控的工具，断言拒绝三种迟到写入并保留外部写入的新值；原七项网络资源验证继续保留。半关闭输出用于触发工具执行，不把该场景的 EOF 单独当作 root 取消导致关闭的证据。
- 本单元不改变基线公共类型/方法；新增默认钩子位于本分支新增的内部辅助接口，私有 bridge 不扩展 InvocationTasks 的公开方法。无生产依赖变化。最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 483 项（支持模块 349 + 主 Agentic 105 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1730 通过。严格基线 API/测试来源审计保持，main 217 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5188 类及全部既有 BOM 消费通过，生产依赖树未变。快照 `verification/agentic-callback-scope-wip.json`；无新完整阶段标签或 Agentic BOM 支持声明。
- terminal TokenStream、任意自定义传输、异步工具执行器、用户自己创建线程的上下文传播仍待完成；直接 state() Map 写入、外部副作用不受此守卫合同覆盖。成功收尾/完整失败历史/自动重试、同步用户回调与 guard 锁边界及全部其他长期范围继续 pending。


### AI Services executor 上下文组合（WIP，2026-10-22）

- 现有 CapturedContextSupport 只捕获 ExecutorProvider 上下文。四项基础实际 Java 8 测试中三项先失败（owner 丢失），无上下文的原 executor/null 合同用例通过；自定义工具 executor 的迟到写入也复现为三次写入全部未拒绝。工具续传请求原先仍在 HTTP 回调上发起，已有 owner，新增续传用例作为回归而非失败证据。
- captureInto 将当前 CancellationContext owner 与原 SPI CapturedContext 组合，保留 provider 的捕获/包装时机和恢复合同；toBuilder 继续沿用不可变捕获快照。既有工具及完成响应 executor 的 restoringIn 因此携带同一 root 归属，不替换或关闭调用者 executor，不改 Guardrails 实现。
- 四项基础用例验证无 provider 时的 owner、InvocationContext 复制、与用户 ThreadLocal 快照组合、worker 原值恢复、callback Error 原对象与上下文恢复，以及无上下文的 executor/null 合同。
- 两项新增 SSE 子进程使用自定义单线程工具 executor：失败后释放门控工具，拒绝三种 scope 写入且保留外部新值；工具在线程中创建的新 HTTP future 立即取消；正常工具续传保持既有归属。验证工具确实运行在指定线程、executor 未被框架关闭，并在同一线程复用时无 scope/resource 残留。原八项网络场景保留。
- 完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 489 项（支持模块 353 + 主 Agentic 107 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1736 通过。严格基线 API/来源审计保持，main 217 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5188 类及全部既有 BOM 消费通过。快照 `verification/agentic-executor-context-wip.json`。没有公开签名或生产依赖变化，Agentic 仍不纳入默认 BOM，无新阶段标签。
- 本单元传播上下文，不宣称工具线程已被强制终止或所有排队任务已取消。完整 executor 任务所有权/中断/排队取消、terminal TokenStream、自定义模型回调/传输、用户自行创建线程、成功收尾与失败历史/自动重试、guard 锁边界和全部其他长期范围继续 pending。


### 同轮多工具取消与清理异常传播（WIP，2026-10-22）

- 扩展 loopback HTTP/SSE 夹具，每次第一响应包含 call1/Shanghai 与 call2/Beijing 两个工具，第二响应正常结束。十项实际 Java 8 多工具测试中九项先通过；一项复现清理错误在高层 Future 包装之间丢失，而另一个工具仍成功取消。不把该失败解释成取消循环未处理其他工具。
- 四项通用取消传播测试中三项先失败：直接清理异常、嵌套清理异常和多路径去重；普通完成/null root 合同原本通过。CompletableFutureUtils.propagateCancellation 现将直接 cancel 抛出的 RuntimeException/Error，以及已取消下游 Future 收集的 suppressed 错误，传到上游取消异常；按对象身份去重并避免 self-suppression。只在已取消/已完成的下游读取该信息，不在尚未完成的拒绝取消 provider 上挂等待回调。
- 十项 HTTP/SSE 用例覆盖 Future/Publisher 同轮两个 pending provider、一个运行/一个排队、一个 provider 拒绝取消但继续清理另一工具，以及成功路径的结果 ID 对齐、补偿模式逆请求顺序回滚一次。补偿测试故意反序完成两个原始 Future；默认取消不继续请求模型，调用者池保持打开，排队工具不执行。
- 拒绝取消的 provider 仍需要自行结束；响应式取消失败按既有日志路径记录，不向已取消订阅补发 terminal。取消传播诊断不是外部副作用回滚或强制终止用户 I/O 的保证。Guardrails 代码保持未改动。
- 新增受影响 core CompletableFutureUtils 的固定基线 API 审计；其余已有 API/来源/产物/链接和消费验证继续保留。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：Java 8 验收 546 项（支持模块 408 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1793 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接及全部既有 BOM 独立消费通过；严格 API/原始测试来源/有效依赖树审计通过。新增 core helper 对照为 1 可见类型/1 声明成员/10 继承公开条目/0 record，无公开成员缺失或新增；本轮没有新增生产类型或依赖。快照 [agentic-tool-fanout-cancellation-wip.json](verification/agentic-tool-fanout-cancellation-wip.json)。Agentic 仍 WIP、未进入默认 BOM。
- 完成响应 executor、terminal 流、完整成功/失败收尾、所有 Agentic 四模块能力、剩余 MCP/模型/向量库/BOM 与最终交付继续 pending；本单元不创建完整阶段标签。

### 高层 Future/Publisher 的工具轮次取消（WIP，2026-10-22）

- 修正 loopback SSE 夹具，使连续 tool 模式也能发送工具事件，之后十二项实际 Java 8 端到端测试先复现十项失败：Future/Publisher 未取消程序化或注解 Future、未跳过排队工具、未中断运行工具、指定调用的取消未到达其工具。两个启用补偿的 drain/rollback 用例原本通过，不把夹具早期缺失的工具事件当有效失败证据。
- AsyncToolLoop 在工具 supplier 提交前登记调用者取消，后创建的工具也立即观察已取消信号；memory 添加之后再次检查取消，避免继续启动新轮次。单次 ToolTask 继续负责运行 worker、排队入口与迟到 Future，调用者池保持打开。取消清理失败附加到调用者取消异常，不把它转成新的模型请求。
- 响应式 Loop 为每个订阅维护并发活动工具集合，取消时遍历快照，工具完成解除登记，发布窗口之后再次检查取消。缺失 CompleteToolCall 时启动的回退工具同样登记。取消清理异常写日志并继续取消其他工具，不在已取消订阅上发 onError/onComplete。
- DefaultToolExecutor 新增私有 AsyncToolResult，将取消穿过注解异步值转换传到原始 CompletableFuture，保留 mayInterruptIfRunning 和清理异常。另两个定向用例先失败：注解工具原始 Future 独立取消被错误转文本并成功返回。现保留 CancellationException，绕过工具错误文本策略；高层 Future 作为失败交给调用者（不冒充主动 cancel），Publisher 正常收到 onError，不发下一轮请求。
- 显式启用 compensateOnToolErrors 时保留固定基线 drain-then-rollback：已启动工具不因高层取消丢失迟到成功结果，待其完成再收集并补偿一次。它不同于默认模式的 best-effort 取消，不承诺补偿模式能强制停止未知外部副作用。相应两条 HTTP/SSE 回归继续验证回滚一次、无下一模型请求。
- 新增十六项实际 Java 8 用例覆盖上述调用模式、隔离、补偿、独立取消、注解 Future 转换的取消模式升级/清理失败。既有 25 项任务/单工具/归属回归全部保留。新增受影响 DefaultToolExecutor 的固定基线 API 对照脚本，不将局部审计当整个主模块 API 审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 532 项（支持模块 394 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1779 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接及全部既有 BOM 独立消费通过。ToolService 5 类型/89 声明/117 继承公开条目/4 record，DefaultToolExecutor 2 类型/17 声明/30 继承公开条目/0 record，严格基线对照无公开成员缺失或新增；Agentic/patterns/受影响 core helper API 与原始测试来源审计通过，生产依赖树未变。快照 [agentic-tool-loop-cancellation-wip.json](verification/agentic-tool-loop-cancellation-wip.json)。Agentic 仍 WIP、未纳入默认 BOM，无新完整阶段标签。
- 本组 HTTP/SSE 取消夹具每次模型响应只含一个工具；同一响应中多工具 fan-out 取消仍需专门验收。任意拒绝取消的 provider/CompletionStage 适配器、同步阻塞回调或 inline executor 不因此获得强制停止与新超时保证；Guardrails 不纳入本单元改造。完成响应 executor、terminal 流、完整成功/失败收尾、所有 Agentic 四模块能力、剩余 MCP/模型/向量库/BOM 与最终交付继续 pending。

### 单次工具调用的组合 Future 取消（WIP，2026-10-22）

- 六项实际 Java 8 定向测试先失败五项：同步 CancellationException 被错误策略转为成功文本、直接取消未到达 provider Future、运行 supplier 未中断、排队 supplier 仍执行、cancel(false) 后迟到的 provider Future 丢失。成功完成后取消返回 false 的用例原本通过。
- startTool 用私有 ToolTask 管理单次调用的 supplier worker、启动 Future 与 provider 原始 Future。直接取消先标记本任务并传播，再终止对外 Future；队列出队不执行 supplier，运行 supplier 在 cancel(true) 时中断，迟到 Future 按已记录模式取消，cancel(false) 可以升级为 true。线程引用在归还调用者 executor 前清除；不关闭调用者 executor，不中断队列中其他任务。
- Future 完成/取消回调均在登记锁外执行；provider cancel 抛异常时继续处理其他已登记 Future，最终仍终止对外 Future，并向取消调用者保留清理错误及 suppressed 错误。root 归属先于迟到 Future 的直接取消处理登记，清理失败不送进工具业务错误策略。provider 同步抛出 CancellationException 现与返回 Future 的取消一样绕过错误转文本策略。
- 新增八项实际 Java 8 验收覆盖上述五个缺口、成功后取消、false→true 升级与 provider cancel 抛错。既有 8 项委托工具（含 loopback HTTP 正常续传/取消后无下一请求）和 9 项基础任务取消回归保留。
- 新增 `audit-tool-service-api.sh`，从固定 Git 导出 ToolService 并编译 Java 17 对照。首次严格对照发现四个 record 替代类的 12 个自动值方法漏 final；补齐修饰符，保留比较器原有严格规则。该检查只覆盖受影响 ToolService 与其可见嵌套类型，不代表整个主模块 API 审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 516 项（支持模块 378 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1763 通过。33 JAR/6382 基础类条目、4954 类默认链接、5193 类扩展链接与全部既有 BOM 独立消费通过；严格 ToolService/Agentic/patterns/受影响 core helper API、原始测试来源及生产依赖树审计通过。ToolService 无公开成员增项，12 个值方法修饰符恢复基线；新增私有 ToolTask 及 javac 合成访问标记。快照 [agentic-tool-cancellation-wip.json](verification/agentic-tool-cancellation-wip.json)。仍 WIP、无新完整阶段标签，不将 Agentic 纳入默认 BOM。
- 单次 startTool 的直接取消不代表高层 AI Service Future/Publisher 或整个工具轮次的反向传播已完成；这些路径继续验收。自定义 Future/I/O 可拒绝取消，忽略中断的用户代码不能强制停止；同步用户回调阻塞没有新增超时保证。完成响应 executor 任务生命周期、terminal 流、完整成功/失败收尾及全部 Agentic/MCP/模型/向量库与交付范围仍 pending。

### 委托异步工具 Future 的归属（WIP，2026-10-22）

- 四项定向实际 Java 8 测试先复现三处失败：supplier 已返回后委托 Future 未取消，成功 after 通知和错误处理均丢失 owner；无 owner 的隔离用例原本通过。
- ToolService 在拿到 ToolExecutor.executeAsync 返回 Future 后登记 root 取消，保留独立等待结果；成功/失败完成时进入捕获的 CancellationContext，恢复原绑定并解除登记。取消在 provider 返回前发生时，迟到 Future 登记后立即取消。
- 通过返回 Future 报告的 CancellationException 不再转交工具错误策略，不生成可送回模型的工具成功结果；避免自定义“错误转文本”策略把取消变为下一轮模型请求。已有 AsyncNotSupportedException 的显式失败行为保留。
- 八项实际 Java 8 验收覆盖 root 取消、异线程成功通知/原异常、无 owner 隔离、provider 返回时取消、提前完成/解除登记，以及 loopback OpenAI HTTP 的正常工具结果回传与取消后无后续请求。该组使用可取消 CompletableFuture，不声称可强制停止拒绝取消的第三方 I/O。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 508 项（支持模块 370 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1755 通过。33 JAR/6378 基础类条目、4952 类默认链接、5191 类扩展链接、全部既有 BOM 独立消费，以及严格 API/测试来源审计通过；生产依赖树无变化，无新增公开声明或类。快照 [agentic-delegated-tool-wip.json](verification/agentic-delegated-tool-wip.json)。Agentic 继续 WIP、未纳入默认 BOM，无新完整阶段标签。
- 当前只证明 root 归属取消；直接取消 startTool 返回的组合 Future 到委托 Future 的反向传播，以及 provider 同步抛出 CancellationException 时的策略，仍待实现/验收。完成响应 executor、terminal 流、自定义传输、全部 Agentic/MCP/模型/向量库及交付范围继续 pending。

### 工具 supplier 的中断与排队取消（WIP，2026-10-22）

- 两项真实 Java 8 SSE 子进程先失败：运行工具没有中断，排队工具 future 未终止导致 HTTP 完成回调继续等待。现工具 supplier 提交到 executor 之前按 InvocationContext owner 登记，取消立即终止 future；运行任务中断实际 worker，排队任务出队时跳过 supplier。调用者 executor 与其中不属于本调用的阻塞任务保持运行。
- 新增内部 CapturedContextSupport.supplyAsync，使用私有 OwnedTask/OwnedCapturedContext/RestoringExecutor。future 完成回调不在 worker 锁中执行；worker 引用在归还 executor 前清除；cancel(false) 后运行 supplier 仍保持 owner 登记，root 后续可中断。排队取消清除本任务持有的 supplier、上下文命令和登记，不承诺从任意用户 executor 的物理队列移除 entry。
- 同一捕获上下文的 restoring executor 可识别并避免重复包装；无 owner 时仍走标准 CompletableFuture 路径。TokenStream 工具调度与 ToolService 并行/启动 supplier 接入统一入口，保留调用者执行器；Guardrails 实现不修改。
- 九项实际 Java 8 基础测试覆盖运行中断、排队 future 立即终止且出队不执行、cancel(false)、拒绝提交原异常/解除登记、已取消 owner 跳过提交、SPI 单次包装/恢复和 supplier 原始错误；另外复现并修复上下文清理 Error 被吞，以及调用者提前完成排队 future 时登记未解除。已发布结果保留，后续 provider 清理 Error 继续交给 executor 的 uncaught-error 通道。
- 新增的两项 SSE 场景验证工具业务方法确实被中断，或仍被调用者工作占住的 executor 中排队工具被取消；HTTP 回调在释放夹具 blocker 前已退出，之后出队也不执行工具。既有迟到写入夹具改为故意忽略中断，以继续验证拒绝三种迟到写入，未删除原断言。
- 固定基线受影响 core helper 的严格 API 对照新增 audit-captured-context-api.sh；既有签名保持，明确报告新增 public @Internal supplyAsync(Supplier,Executor,InvocationContext)。该审计只覆盖受影响 helper，不冒充整个 core 的完整 API 对照。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 500 项（支持模块 362 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1747 通过。33 JAR/6378 基础类条目审计、4952 类默认链接与全部既有 BOM 独立消费通过；main 217 类/patterns 22 类均为 classfile 52、无禁止引用，扩展链接 5191 类。严格 Agentic/patterns API、受影响 core helper API 及原始测试来源审计通过，生产依赖树未变。快照 [agentic-owned-task-wip.json](verification/agentic-owned-task-wip.json)。Agentic 仍不纳入默认 BOM，无新完整阶段标签。
- 任意忽略中断的用户代码不能强制停止，物理队列属于调用者；自定义 ToolExecutor.executeAsync 返回的委托 stage 取消、完成响应 executor 的完整任务生命周期、terminal TokenStream、自定义传输、成功收尾与完整失败历史/自动重试、guard 锁边界及全部长期范围继续 pending。
