# Java 8 移植进度

## 当前状态：第十五阶段主 Agentic 基础移植检查点（WIP，2026-10-21）

- 上一完整阶段标签仍为 `jdk8-m14`。Agentic 主模块已开始改造，尚未通过完整功能验收或纳入 BOM；整体目标未完成。
- 四模块 187 文件的固定基线声明快照仍见 [AGENTIC-AUDIT.md](AGENTIC-AUDIT.md) 与 [AGENTIC-API-INVENTORY.json](AGENTIC-API-INVENTORY.json)。当前移植与覆盖边界见 [AGENTIC-PORT.md](AGENTIC-PORT.md)。
- `agentic-wip` 显式 profile 编译主模块全部 158 个基线生产文件及 1 个新增兼容辅助文件，保留默认八模块支持范围。生产和专项测试均 release 8；14 项基础行为和 2 项恢复场景在真实 Java 8 执行。
- 主模块 25 个 record 已转为 POJO，补齐 JSON 构造元数据、访问器与自动值方法 final 修饰符；保留对象相等性。高版本 API、switch/模式匹配/text block/var 已降级，并修正模式绑定的重复读取。私有接口辅助方法移入包内类，避免扩大接口 API。
- 新增固定 Git 源码 → Java 17 对照产物 → Java 8 候选的二进制 API 审计，覆盖可见类型、声明/继承成员和 bridge；不将声明数量当成功能验收。验证命令与快照见 AGENTIC-PORT.md。
- 主模块有效生产依赖树仅包含兼容 main/core 与既有 Jackson、SLF4J、JSpecify、Reactive Streams、OpenNLP，无官方 SNAPSHOT 或新增生产库。其余 Agentic 三模块的有效依赖/功能仍待完成。
- 下一单元：主 Agentic 的剩余 IT/端到端回归、async/streaming、HITL/挂起恢复、记忆与补偿、planner/supervisor/声明式和可观测性；再推进 patterns/MCP/A2A 的全部能力、BOM 和独立消费。MCP 旧双端点及其他剩余能力、全部模型/向量库继续保留。

### 恢复与原始单元回归检查点（WIP）

`./jdk8/build.sh -o -Pagentic-wip` 新一轮完整 clean exit 0；最新快照：[agentic-recovery-wip.json](verification/agentic-recovery-wip.json)。

- 支持模块真实 Java 8 346 项，主 Agentic 16 项；新增两个独立 Java 8 进程的文件检查点恢复，执行日志严格为 prepare/ask/finish，恢复不重放前两步。
- 默认 JDK 17 选定上游 864 项，加上全部 51 个固定基线 Agentic 单元测试类 251 项。原断言保持不变；fixture audit 校验 51 个选择项及 58 个原始测试/辅助源文件内容。
- TCK 62 通过、14 官方 untested；本轮共 1539 通过、14 官方 untested，所有其余测试无失败/错误/跳过。API 对照、208 类附加 JAR 审计、5154 类实际 Java 8 链接及全部既有 BOM 示例均通过。
- `agentic-upstream-tests` 为 JDK 17 独立测试 harness；复制的共享流式夹具仅调整两个 Flow 导入，Invoice 嵌套夹具经固定基线 AST 校验，未加载外部模型 IT 配置。测试来源及边界见 harness README。
- Agentic 的完整 IT/端到端、其余模块与 BOM 消费仍待完成，当前不新增阶段标签。

### 基础移植检查点 `303f41467` 验证

`./jdk8/build.sh -o -Pagentic-wip` clean 全流程 exit 0；快照：[agentic-main-wip.json](verification/agentic-main-wip.json)。

- 原八模块真实 Java 8 346 项、主 Agentic 新增 14 项，均无失败/错误/跳过。
- JDK 17 选定上游仍为 864 项；Publisher TCK 62 通过、14 官方 untested。总计 1286 通过，14 官方 untested 单列。
- 原八模块 33 JAR / 6366 类审计与全部 BOM 示例通过；新增 Agentic JAR 208 类均 classfile 52，无禁止类引用。加入该 JAR 后真实 Java 8 链接 5154 类，仍只排除原 3 个可选平台入口。
- 固定基线主 Agentic API 对照通过：185 可见类型、1317 声明成员、2817 继承/公开项、22 可见 record 替代；无遗漏/改变成员或新增接口成员。Record 父类/反射元数据差异显式记录。
- 这是 WIP 证据，不替代完整 Agentic 功能/BOM 消费验收，因此不新增阶段标签。

## 第十四阶段现代 stdio/WebSocket 已完成（2026-10-21）

- 仓库 `/Volumes/Nas/codex-work/langchain4j-jdk8`，分支 `jdk8/main`；当前完整验收 `jdk8-m14`，上期 `jdk8-m13`；固定基线 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。
- 八个生产模块和兼容坐标不变，无新增生产依赖；整个 MCP、Agentic、模型/向量库及最终交付仍未全部完成。
- 固定基线全部 120 个 POM 的候选清单继续维护，不将声明审计计为功能实现或排除较难模块。

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

17. MCP 会话 404 共享重新初始化、能力/缓存刷新、有界 DELETE、可选 GET SSE 与逐流 Last-Event-ID 恢复；并发/取消/超时/资源所有权和重试次数验证，原始工具 POST 不自动重放。

## 最终验证

`./jdk8/build.sh -o` 完整 clean、独立 BOM 消费及产物审计 exit 0。快照：[milestone-14.json](verification/milestone-14.json)。

| 验证 | 第十四阶段结果 |
|---|---|
| 八模块生产源码 | JDK 17 `--release 8` 通过 |
| 真实 Java 8 | 346 项，0 失败/错误/跳过；1.8.0_462 |
| JDK 17 选定上游 | 864 项，0 失败/错误/跳过；17.0.13 |
| 官方 Publisher TCK / Java 8 | 62 通过、14 官方 untested；无失败/错误或 required/optional 跳过 |
| 字节码/API 审计 | 33 JAR / 6366 基础类 |
| Java 8 类及成员链接 | 4946 类，保留 3 个可选平台排除 |
| 所有旧 BOM 示例 | AI Services、RAG、HTTP embedding、SQLite 跨进程、MCP 各此前传输/协议均通过 |
| 新增现代 stdio/WebSocket BOM | 每种发现 1、状态续传 1、实际执行 1、取消 1、订阅及进程/通道释放 |

新增 39 项 Java 8（34 个双通道参数化用例、5 个现代 WebSocket 恢复用例）；共 1272 项通过，另列 14 官方 untested。单方法筛选参数化测试曾选中 0 项，不计任何验证；最终完整套件有明确非零类/用例证据。

## 已知边界

- Java 8 的 Flow 签名替换、record/sealed 降级和线程模型差异继续适用；具体约定见 `COMPATIBILITY.md`。
- OpenNLP 2.x 不能运行于 Java 8。使用 1.9.5 和官方 0.35.0 历史中的 1.9.3 句子模型，未修改模型版本绕过校验；Java 8 和 6 个原始分割用例通过。1.9.x 已结束维护，模型结果可能与新版不同，来源和生命周期限制已记录。
- 基础 RAG 已通过本地模型和真实 OpenAI HTTP embedding 适配器两种流程验证（服务均为 loopback fixture）；真实远程模型/语义质量、其他 embedding 适配器/外部向量库、easy-rag 自动下载、复杂重排/多模态 RAG 尚未验收。Guardrails、Decision Services、复杂补偿事务、其余第三方适配器与 OpenAI Responses/音视频/图像仍保留未覆盖声明。
- SQLite 为精确全表扫描、Java 端过滤及阻塞 JDBC，异步 RAG 必须显式 offload；取消不能保证打断已开始的数据库扫描。未验证其他 JNI 平台、网络文件系统锁、同时多进程写和断电恢复。
- SQLite 会话更新只保证完整快照原子性，同会话并发 AI Services turn 需应用串行化；取消不回滚已提交历史。记忆 ID 支持 String/Integer/Long/UUID，异步需显式配置调用方 Executor，不承诺任意 Object ID 或原生异步 JDBC。
- MCP 已覆盖 2025-11-25 HTTP 工具、会话与 SSE 恢复；现代 HTTP 2026-07-28 子集已验收；现代 stdio/WebSocket 已专项验收；原始 2024 双端点、跨进程持久化游标及完整多模态业务等仍待推进；WebSocket 2025-11-25、服务器 ping/roots、WSS 与恢复已新增网络验收；Registry 已按固定基线查询接口验收；stdio 与资源/提示词范围已新增专项验收。直接 MCP Future 取消会关闭 HTTP；AI Services 则保留已启动工具 drain 到结束/超时的上游约定。
- 已运行内部 ZeroPublisher 的官方 Publisher TCK（62 通过、14 项官方 untested），未运行所有适配器的 Subscriber/Processor/Flow TCK，不声明全组件规范认证。有限并发测试不是所有线程调度的证明；类链接仍排除三个可选宿主平台入口。

## 后续范围：跳过 Guardrails

1. 用户明确要求跳过 Guardrails 代码；后续不开展其专项移植、修复或验收，不将其标为已支持。
2. 现有源码及此前已完成的验证结果保留；该范围调整不要求删除源码或拆除已有调用路径。
3. 第六、七阶段已完成 SQLite 向量和会话记忆持久化；后续可推进 MCP/Agentic/其他集成，不再默认推进 Guardrails。
4. 继续保留真实 Java 8、JDK 17 选定回归、官方 Publisher TCK、独立 BOM 与产物审计，以及按检查点提交的约定；不自动跟随上游、推送或建立后台任务。

## 下一阶段：Agentic 移植前审计与 MCP 剩余能力

按 ROADMAP 继续实现并验收，之后推进全部 Agentic 相关能力及逐模块模型/向量库。保持取消、超时、资源释放和不自动重放工具请求；外部真实服务联调需单独授权，Guardrails 继续排除。单个里程碑结束不代表整体目标完成。

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

| `cc15813e5` | 第九阶段会话/SSE 恢复完整范围（当时 WIP） |
| `17765ebb8` | 共享会话初始化、逐流 GET 恢复、DELETE/取消/所有权与故障验收 |
| `eb2c6f1be` | GET 503 重试预算修复，32 个恢复验收 |
| `04ef31620` | GET Retry-After 的 0/00 等价写法均纳入同一预算 |
| `jdk8-m9` | 第九阶段最终 clean：1117 项通过、14 项官方 untested；会话恢复独立消费和审计通过 |

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

## 第九阶段历史检查点（当时 WIP，现已完成）

- 目标：MCP 会话过期重新初始化、显式 DELETE 释放、可选 GET SSE 通道、POST/GET SSE 断线恢复与 Last-Event-ID；并发、取消、超时及资源释放全部验收后提交阶段标签。
- 从干净的 jdk8-m8 恢复，外置盘已挂载、约 69 GiB 可用。继续固定 MCP 2025-11-25；Guardrails 跳过，不连接外部服务器或读取其他凭据。
- 约束：恢复不重发任何原始 POST；失效会话的在途请求明确失败，新请求等待共享初始化。SSE 恢复只 GET 同一流游标；重试有间隔/次数及请求总时限。关闭停止全部重试并以有界 DELETE 尝试释放已知会话。
- 该检查点当时尚未验收；现已通过上方完整 clean 构建、真实 Java 8/上游/TCK、独立 BOM 与产物审计。

### 第九阶段实现检查点（当时 WIP，现已完成）

- 实现会话代际、共享初始化、404 在途失败/后续新请求等待、能力和缓存失效；使用独立负数恢复请求 ID，不重发原 POST。
- 新增 SSE 读取器，支持 UTF-8/BOM、CR/LF/CRLF、完整事件游标提交、retry 字段、空/无效/未完成事件；POST 断线仅 GET 恢复，GET 子通道独立游标/dispatcher、405 降级和有界连续失败重试。
- 新增 terminateSession/有界 DELETE、幂等 close、调用方客户端所有权与自建资源关闭。请求总 deadline 跨恢复生效，取消通知绑定原会话；通过 one-shot body 禁止 OkHttp 503/421 等隐式 POST 重发。
- 聚焦真实 Java 8：新增 31 项恢复验收和原有 24 项 MCP 均已通过；随后继续补强 close 截止与握手并发收尾，并将独立 BOM 的 MCP 示例增加 recovery 模式，随后完整 clean 验收通过，见上方及 milestone-9 快照。
- 记录诊断：成功响应后的 Call.cancel 清理已修正；原生 OkHttp 对照定位另一个 EOF 为 Java 8 HttpServer 空 GET/DELETE 请求体未初始化造成夹具连接关闭，夹具已修复，未以重试 POST 掩盖错误。

### 第九阶段重试边界修复（当时 WIP，现已完成）

- 最终代码核对新增 503 Retry-After:0 的 GET 计数验收：修复前配置 2 次恢复却收到 6 个 GET（预期 3），复现了 OkHttp 自身的隐式跟进。
- GET 网络拦截将该响应交回 transport 的重试调度，单次请求次数与间隔现在受同一策略控制；新增用例通过，MCP 恢复验收增加到 32 项。最终 clean 构建、独立恢复消费和运行时审计随后全部通过。

### 第九阶段最终验收

- `./jdk8/build.sh -o` 以 exit 0 完成；生产模块 release 8，205 项 Java 8（1.8.0_462）/850 项上游（17.0.13）/62 项 Publisher TCK 通过，14 项官方 untested 单列。
- 33 JAR/6294 基础类审计、4910 类 Java 8 成员链接通过，保持三个既有可选平台排除；无新增生产依赖。
- 独立 BOM recovery 示例确认 sessions=2、expired POST=1、executed tools=1、resume GET=1、DELETE=1；所有旧 AI Services/RAG/SQLite 跨进程消费仍通过。
- `verification/milestone-9.json` 逐项列出目标与当前测试证据；进度、兼容差异和模块使用说明已更新。未修改 Guardrails、相邻原始仓库、宿主服务；未推送或发布。


## 第十阶段最终验收

- stdio 生命周期改造和 19 项真实 Java 8 子进程验收完成，HTTP 资源/提示词等 14 项完成；新增独立 BOM 子进程示例。
- 复现并修复资源/提示词中断不取消 HTTP（2 项）和查询途中通知被旧响应覆盖（3 项）；保留确定性回归，关闭期流异常不再逃逸到调用方 executor。
- 最终 clean exit 0：238 Java 8、858 JDK 17、62 TCK 通过；14 官方 untested 单列；33 JAR/6302 基础类审计、4914 类链接和所有消费示例通过。
- 全量模块候选清单只是范围记录，Agentic 与其余模型/向量库尚未实现，不计完成。未修改相邻源码仓库/Guardrails/宿主服务，未推送或发布。


## 第十一阶段最终验收

- Registry 全部六个基线同步 API、模型与 Java 8 语法/API 降级完成；查询参数分别编码、路径单段编码和纯点段校验，headers 不再修改调用方 Map。
- 12 项真实 Java 8 覆盖嵌套数据、时间、分页、headers、32 并发请求、错误/JSON/超时恢复；新增三类 6 项完整上游回归。首轮模型扫描因 JAR CodeSource 失败，测试 harness 使用同次 MCP 生产类目录后原测试通过；独立消费及审计仍用 JAR。
- 最终 clean exit 0：250 Java 8、864 JDK 17、62 TCK 通过；14 官方 untested 单列；33 JAR/6354 基础类审计、4940 类链接和所有旧/新消费通过，无新增生产依赖。
- 固定基线 v0/v0.1 的本地 HTTP 验收不证明公共 Registry 当前互操作；同步接口没有原生 async/请求级取消。整体目标仍未完成，继续 WebSocket/其他协议及后续全部模块。


## 第十二阶段最终验收

- WebSocket 改用现有 OkHttp，真实 Java 8 验证工具、分片/控制帧、WSS、连接代际、共享恢复、取消/时限、缓存刷新、服务器 ping/roots、关闭及资源所有权；无新增依赖。
- 26 项新验收包括 20 在途失败且不重放、20 后续调用共享一握手、单等待者取消不影响共享恢复、握手/恢复失败后的新调用重试、重复 ID 和队列/在途上限。
- 完整 clean exit 0：276 Java 8、864 上游、62 TCK 通过；14 官方 untested 单列；33 JAR/6364 基础类审计、4945 类链接及所有旧/新 BOM 示例通过。WebSocket 示例 wire 次数确认没有重放失效工具。
- 原 WebSocket listener/JDK 类型签名及单参数 TLS/executor builder 有迁移差异，见兼容表；其他协议和剩余 MCP/Agentic/模型/向量库仍未完成，整体目标保持推进。


## 第十三阶段最终验收

- 接通固定基线 2026-07-28 HTTP server/discover、方法/名称/参数头、无旧会话与 subscriptions/listen；默认版本不变。现代 404/取消不触发旧 initialize/DELETE 或重放工具。
- 31 项实际 Java 8 覆盖订阅确认/拒绝/超时/中断/EOF/释放、八个并发长流、版本回退、续传、异步工具当前轮次取消/超时/轮次上限，以及并发响应/确认流绑定。
- 已复现并修复订阅中断泄漏、默认五个单主机长流槽位饥饿、完成回调中错误 busy；同步请求状态续传和工具中断也释放当前请求。确认前 deadline 有界，匹配确认后取消 unary deadline，流并发默认 64 可配。
- 完整 clean exit 0：307 Java 8、864 上游、62 TCK 通过；14 官方 untested；33 JAR/6366 类审计、4946 类链接及全部旧/新消费通过，无新增生产依赖。
- 现代 EOF 新订阅由应用显式发起；交互 inputRequests 沿用基线明确拒绝。旧 2024 这里只验证单端点版本字段，不代替双端点协议支持。其他 MCP、全部 Agentic、模型/向量库与最终交付仍未完成，整体目标保持 active。


## 第十四阶段最终验收

- 现代 WebSocket 与 stdio 共用高层协议：发现、确认型长期订阅、UTF-8、状态续传、取消/超时/中断、旧版本回退和关闭通过实际 Java 8 验收。
- 复现并修复 stdio 服务器取消回显及真实进程重启缓存未失效；现代 WebSocket 恢复、健康探测首先恢复时均刷新能力和自动列表订阅。自动探测后的消息使用协商版本，不再复用空配置版本。
- 39 项新增用例含 20 个等待者共享恢复、观察/单等待者取消隔离、失败恢复后新调用、旧订阅不在新连接取消、资源所有权和进程释放。
- 最终 clean：346 Java 8、864 上游、62 TCK 通过，14 官方 untested；33 JAR/6366 类审计、4946 类链接及所有旧/新独立消费通过，无新增生产依赖。
- 不自动重放失效工具；原始旧 HTTP+SSE 与更多未验收能力、全部 Agentic、模型/向量库仍未完成，整体目标保持 active。
