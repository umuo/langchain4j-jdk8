# Java 8 移植进度

## 当前状态：第四阶段 Embeddings API 与 RAG 传输验收进行中（WIP，2026-10-20）

- 仓库：`/Volumes/Nas/codex-work/langchain4j-jdk8`；分支 `jdk8/main`；当前完成标记 `jdk8-m3`；历史标记 `jdk8-m1`、`jdk8-m2`。
- 固定上游基线：`7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。相邻原始仓库未修改；完整 Git 历史独立保存，不共享对象文件。
- Maven 坐标：`dev.langchain4j.jdk8:*:1.23.0-jdk8-SNAPSHOT`，兼容 BOM 现覆盖六个生产模块。已安装到外置盘 Maven 缓存；未远程推送或公共发布。

## 已完成能力

1. 首期五模块：core、HTTP 抽象、OkHttp、reactive-streaming、OpenAI 的 Java 8 源码与 Chat Completions 闭环。
2. 第二阶段加入 `langchain4j` 主模块：AI Services 接口代理、继承/非 public 接口的默认方法、可变参数/空数组/null、原始异常、注解提示词和可替换元数据 SPI。
3. 结构化 POJO 输出、Future、TokenStream 和 Reactive Streams Publisher；同步/异步会话隔离，以及 ChatMemoryAccess 查询与清理。
4. 自动工具循环：同步、Future、回调 SSE、响应式 SSE 四条路径，真实 loopback HTTP 联动，参数/结果回传、并发工具、失败和轮次限制。
5. Future/Publisher 取消后 HTTP 调用释放；内部取消专用回调与正常完成/失败区分。Java 17 下仍可反射读取 record 元数据。
6. 基础 RAG 全链路：加载、多文档分段/index/元数据、内存向量检索及过滤、JSON/文件持久化恢复、AI Services 上下文注入与来源；同步、Future、回调/响应式流式和 loopback HTTP/SSE。
7. 修复 RAG embedding 投影及 AI Services memory/augmentation 的取消链；验证检索失败、显式 offload 和保留问题原文的记忆配置。

## 最终验证

执行 `./jdk8/build.sh`，完整 clean install、独立 BOM 消费项目、字节码审计、真实 Java 8 类链接、AI Services 模拟对话和独立 RAG 示例全部通过。

| 验证 | 第三阶段结果 |
|---|---|
| 六个模块生产源码 | JDK 17 `--release 8` 构建通过 |
| 真实 Java 8 验收 | 70 个用例，0 失败/错误/跳过；JDK 1.8.0_462 |
| 选定上游及现代反射回归 | 554 个用例，0 失败/错误/跳过；JDK 17.0.13 |
| 字节码和禁止引用审计 | 28 个 JAR 文件、5840 个基础类通过；含项目构建/安装副本 |
| Java 8 类及成员类型加载 | 4620 个类通过；3 个可选 Android/Conscrypt/OSGi 入口排除 |
| 独立 BOM 消费示例 | Java 8 通过 AI Services 返回 `Java 8 OK` |
| 独立 BOM RAG 示例 | Java 8 返回 `7 yuan` 和对应 apple 文档来源 |
| 模型资源检查 | 兼容 JAR 只包含配套 1.9.3 模型，未打入 2.5.0 模型 |
| diff/shell 检查 | `git diff --check`、`sh -n jdk8/build.sh` 通过 |

结果快照：`verification/milestone-3.json`；历史快照保留为 `verification/milestone-2.json`（51 个 Java 8 / 435 个上游用例）和 `verification/milestone-1.json`（25 / 228）。当前详细 JUnit 报告位于两个测试模块的 `target/surefire-reports`。这些是选定回归，不代表上游全量/Kotlin 测试已运行。

本阶段上游回归新增段落分割、检索器及 async、默认 augmentor、注入器、聚合/融合、查询转换/路由、内存过滤和异步存储 RAG。第二阶段 AI Services 等回归继续保留。工具夹具按原始 arg0/arg1 参数约定编译，生产参数名保留不变。

## 已知边界

- Java 8 的 Flow 签名替换、record/sealed 降级和线程模型差异继续适用；具体约定见 `COMPATIBILITY.md`。
- OpenNLP 2.x 不能运行于 Java 8。使用 1.9.5 和官方 0.35.0 历史中的 1.9.3 句子模型，未修改模型版本绕过校验；Java 8 和 6 个原始分割用例通过。1.9.x 已结束维护，模型结果可能与新版不同，来源和生命周期限制已记录。
- 基础 RAG 使用确定性本地 embedding 验证流程；真实语义质量、外部 embedding 服务/向量库、easy-rag 自动下载、复杂重排/多模态 RAG 尚未验收。Guardrails、Decision Services、复杂补偿事务、其余第三方适配器与 OpenAI Responses/音视频/图像仍保留未覆盖声明。
- Reactive Streams 官方完整 TCK 尚未运行；类链接检查排除需要额外宿主平台的三个可选入口，不是对所有方法体的穷尽证明。

## 下一阶段建议：OpenAI embedding 与 RAG 传输闭环

1. 用 loopback 模拟 Embeddings API 验证批量输入、维度、token usage、错误与超时，并覆盖原生 async 和取消。
2. 将确定性向量从测试内模型推进到真实 OpenAI embedding HTTP 适配器，再与现有入库、过滤检索、AI Services 对话串联；不默认访问网络模型。
3. 保持 Java 8 生产依赖审计与独立 BOM 消费验证，按检查点提交。真实模型联调仍需显式地址/密钥环境变量。
4. MCP、Agentic、具体向量库等另行递进；不自动跟随上游、推送或建立后台任务。

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
| `jdk8-m3` | 第三阶段最终 clean 构建、624 个分层用例、消费示例和运行审计完成 |

下一轮先读此文件、`COMPATIBILITY.md` 和 `git status`。一次性转换工具只用于原始快照，不能对已移植源码重跑作为升级方案。

## 第三阶段历史检查点（当时 WIP，现已完成）

- 已从干净的 `jdk8-m2` 恢复；外置盘挂载，约 69 GiB 可用。
- 不新增集成模块，沿用六模块兼容构建；增加确定性本地 RAG 全链路和选定上游回归。
- 此检查点当时尚未验收；当前支持范围以上方第三阶段最终结果为准。

### 第三阶段实现检查点（当时 WIP，现已完成）

- 加入 `RagJdk8Test`：确定性 embedding、多文档分段/index/元数据、过滤/topK/空结果、JSON/文件恢复及删除、文件加载错误、同步/Future/回调/Publisher 提示词注入和来源、记忆原文保留、阻塞 offload 及失败传播。
- 加入 `RagOpenAiJdk8Test`：四种调用路径通过 loopback HTTP/SSE 发送真实 RAG 请求，并验证上下文和 HTTP 调用释放；不依赖真实模型。
- 已复现并修复两个取消断点：检索器 `thenApply` 到 embedding 的回传、AI Services 的异步 memory/augmentation 组合链。补充 Future/Publisher/等待 memory 期间的取消测试，防止继续调用模型或检索器。
- 聚焦验收：15 个 `RagJdk8Test` 在真实 Java 8 下通过；此前完整构建及新增 HTTP/SSE 4 个用例通过。随后已完成含全部新增边界用例的最终 clean 构建，结果见上方和 milestone-3 快照。
- 独立 BOM 的 `RagExample` 已在 Java 8 输出 `Java 8 RAG OK; answer: 7 yuan; source: apple price is 7 yuan.`；关键词向量仅用于验证流程，不代表真实语义质量。

## 第四阶段检查点（WIP）

- 从干净的 `jdk8-m3` 恢复，外置盘挂载、可用约 69 GiB。
- 沿用六模块生产依赖；新增 embedding 请求/响应、listener、异步/重试等选定上游回归。
- 本阶段目标：loopback Embeddings API 的批量/维度/token usage/错误/超时/取消，HTTP embedding 到检索及 AI Services 的完整闭环，独立 BOM 消费示例。
- 当前支持范围仍以第三阶段验收为准，未使用真实网络模型或服务凭据。

### 第四阶段实现检查点（WIP）

- `EmbeddingOpenAiJdk8Test` 新增 19 个真实 Java 8 用例：批量顺序及 token 合并、请求覆写/维度/Base64/headers/listener、错误/重试/超时、全部批次/兄弟任务取消，及 HTTP embedding -> 存储恢复 -> 四条 AI Services HTTP/SSE 路径。
- 已复现并修复：响应 index 被忽略；响应数量与索引/向量缺失未校验；底层 HTTP Future 独立取消导致批量结果一直等待。同步/异步共用响应校验，全部无 index 的兼容提供方仍保留 wire 顺序。
- 新增独立 BOM 的 `HttpRagExample`，使用真实 OpenAI embedding/chat 适配器访问 loopback；显式自定义 OkHttp 时由消费 POM 声明 BOM 管理的 OkHttp 模块。
- 最新 clean reactor 已通过 89 个 Java 8 / 627 个 JDK 17 用例，0 失败/错误/跳过；独立消费示例及运行审计仍待本检查点后确认，尚未标记第四阶段完成。
