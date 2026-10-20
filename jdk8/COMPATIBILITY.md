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
