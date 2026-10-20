# LangChain4j Java 8 兼容工程

基于官方提交 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`（1.23.0 开发基线）的独立兼容分支。保留完整上游历史、Java 包名和主要模型 API，使用独立 Maven 坐标，按阶段提交。

## 当前产物（第七阶段）

以下七个模块已完成 Java 8 生产源码移植，构建版本统一为 `1.23.0-jdk8-SNAPSHOT`：

- `langchain4j-core`
- `langchain4j-http-client`
- `langchain4j-http-client-okhttp`
- `langchain4j-reactive-streaming`
- `langchain4j-open-ai`
- `langchain4j`（AI Services、会话记忆、工具执行和基础 RAG）
- `langchain4j-sqlite`（向量与会话记忆持久化、过滤检索和事务）

这是固定提交的兼容分支，不代表持续同步官方最新版本。当前已验证 AI Services 默认接口方法、注解提示词、结构化 POJO、Future、TokenStream、Publisher、会话隔离/清理及自动工具循环（含并发、失败、轮次限制和取消）。第三阶段新增基础 RAG：文档加载/分段、内存向量存储与恢复、过滤检索、提示词注入与来源、同步/异步/流式调用。第四阶段新增 OpenAI Embeddings HTTP API 的批量、Base64、参数/维度、token usage、错误/重试/超时和取消，并接入四条 RAG 路径。Agentic、MCP、其他 embedding 适配器、具体向量库、Spring Boot 集成及 Jackson 3 尚未业务验收；真实远程模型和语义质量未验证。

## 构建与验收

在仓库根目录执行：

```sh
./jdk8/build.sh
```

脚本检查 `/Volumes/Nas` 挂载，使用现有 JDK 17 构建生产代码（`--release 8`），使用真实 JDK 8 执行独立验收，再用 JDK 17 执行选定的上游测试。成功后安装到外置盘 Maven 仓库、编译独立 AI Services/RAG BOM 消费示例，审计生产 JAR 并在 Java 8 下执行类链接检查、loopback 对话、确定性 RAG 和 HTTP embedding RAG 示例。

可通过 `JAVA17_HOME`、`JAVA8_HOME`、`JDK8_MAVEN`、`JDK8_SETTINGS`、`JDK8_REPOSITORY` 覆盖本机工具路径。默认复用 SDKMAN 的现有 JDK，缓存位于 `/Volumes/Nas/envs/maven/repository`，不会安装或切换全局 JDK。

独立 Maven 入口是 `jdk8/pom.xml`；**原始根 POM 为上游对比保留，不是兼容构建入口**。标准构建请使用脚本以确保运行了 Java 8 验收和上游回归。手动执行 Maven 时须设置 `-Djava8.home=/path/to/jdk8`。

## 在 Java 8 项目中使用

先执行上述构建以安装本地产物，再导入兼容 BOM：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.langchain4j.jdk8</groupId>
            <artifactId>langchain4j-bom</artifactId>
            <version>1.23.0-jdk8-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>dev.langchain4j.jdk8</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j.jdk8</groupId>
        <artifactId>langchain4j</artifactId>
    </dependency>
</dependencies>
```

Java 导入仍为 `dev.langchain4j.*`。通过接口创建服务：

```java
interface Assistant {
    String chat(String message);
}
Assistant assistant = AiServices.create(Assistant.class, model);
```

其中 `model` 为你显式配置的 `OpenAiChatModel`。默认 HTTP SPI 已选择 OkHttp，无需引入 JDK HTTP Client。不要在同一应用中混用官方 `dev.langchain4j` 和兼容版同名模块。

`jdk8/example` 是不继承本工程父 POM 的独立消费项目，使用本地模拟服务，无需 API Key。真实服务联调应自行显式提供地址、模型名和密钥，本工程不会读取宿主机服务配置。

## 句子分割依赖限制

主模块使用 OpenNLP 1.9.5 和官方 0.35.0 历史中的配套英语句子模型，以满足 Java 8。OpenNLP 1.9.x 已结束维护，且与新版默认模型的分句结果可能不同；不要把本兼容分支理解为消除了旧依赖风险。详见 `COMPATIBILITY.md` 中的来源、验证和边界。

## 持续推进

阅读 `PROGRESS.md` 查看已验证结果、检查点提交和下一阶段；阅读 `COMPATIBILITY.md` 查看 API 差异和验证边界。开发者请遵循根目录 `AGENTS.md`。本仓库尚未推送或发布到公共 Maven 仓库。

## 基础 RAG 示例与边界

[`example/src/main/java/RagExample.java`](example/src/main/java/RagExample.java) 展示显式配置 embedding 模型、`DocumentByParagraphSplitter`、`EmbeddingStoreIngestor`、`InMemoryEmbeddingStore` 和 `EmbeddingStoreContentRetriever`，再经 `AiServices` 返回 `Result<String>` 的答复及 `sources()`。`./jdk8/build.sh` 会在真实 Java 8 下运行它，无需网络模型或密钥。

示例的关键词向量用于稳定验证数据流；实际应用应显式替换为经过独立验证的 embedding 模型。当前未验收 easy-rag 自动模型下载、外部向量数据库及真实语义质量。异步检索需要组件提供 async 接口；阻塞组件必须显式设置 `offloadBlocking(true)`，Java 8 下使用普通线程，取消此类任务不保证中断底层阻塞 I/O。

第三阶段结果：70 个 Java 8 验收、554 个 JDK 17 选定回归均通过，快照见 `verification/milestone-3.json`。

## OpenAI embedding HTTP 闭环

[`example/src/main/java/HttpRagExample.java`](example/src/main/java/HttpRagExample.java) 使用 `OpenAiEmbeddingModel` 批量入库，恢复 JSON 存储后，经原生 async embedding 检索，再由 `OpenAiChatModel` 和 AI Services 异步答复。两种模型均通过真实 OkHttp 访问 loopback 模拟服务，无需外部密钥。脚本在真实 Java 8 上运行示例，输出 `Java 8 HTTP RAG OK`。

模型保留 base URL、timeout、API key、组织/项目、自定义 headers/query、参数覆写和 HTTP builder。使用默认传输可沿用原配置；源码中显式引用 `OkHttpClientBuilder` 时，消费项目需额外声明 BOM 管理的 `langchain4j-http-client-okhttp` 编译依赖，见示例 POM。

响应按批次 index 恢复输入顺序，数量不足、重复/混合/越界 index、缺失或空向量会立即失败，避免错误入库；提供方全部省略 index 时仍按响应顺序处理。取消为尽力传递，已验证 OkHttp 调用释放。第四阶段结果为 89 个 Java 8 / 627 个 JDK 17 用例，见 `verification/milestone-4.json`；这不代表真实远程模型、所有 embedding 提供方或语义质量已验证。

## 响应式规范验证

`./jdk8/build.sh` 还会在真实 Java 8 上运行独立 `reactive-tck` 模块中的官方 TCK 1.0.4，验证 ZeroPublisher 的同线程/异步 Publisher 行为；测试依赖不进入生产运行时。两组共 62 项通过，14 项是官方标为不验证的 `untested_*`，未跳过 required/optional 项。精确规则与原因见 `COMPATIBILITY.md` 和 `verification/milestone-5.json`。

第五阶段修复取消/终止后订阅者引用保留，以及原始 AI Services 事件流在 RAG 等待期间忽略非正 demand、立即取消仍启动检索的问题。新增并发信号、终止竞争、回收和真实 SSE 资源释放验收。这里的 TCK 结果限于内部 Publisher 桥接，未声明所有适配器的 Subscriber/Processor/Flow TCK 已通过。

## SQLite 持久化向量存储

新增兼容 BOM 模块 `langchain4j-sqlite`，使用 `dev.langchain4j.store.embedding.sqlite.SqliteEmbeddingStore`。这是本分支新增实现，原始上游基线没有此模块；完整用法见 [`../langchain4j-sqlite/README.md`](../langchain4j-sqlite/README.md)。它支持事务批量 upsert、删除、类型保真的元数据、维度约束及精确余弦 topK/过滤检索。每个文件一个集合，同 ID 更新，首次非空写入固定维度，清空数据仍保留维度。

```java
try (SqliteEmbeddingStore store = SqliteEmbeddingStore.builder()
        .databasePath(Paths.get("/Volumes/Nas/data/catalog.db"))
        .build()) {
    // Pass store to EmbeddingStoreIngestor / EmbeddingStoreContentRetriever.
}
```

数据库父目录需已存在。JDBC 是阻塞接口，异步/响应式 RAG 显式使用 `.offloadBlocking(true)`；取消不保证打断在途 JDBC 扫描。检索是 Java 全表精确扫描，未实现 ANN 或 sqlite-vec 索引。metadata 过滤在 Java 端执行。

`HttpRagExample write <file>` 和 `read <file>` 分别在两个真实 Java 8 进程运行，验证 OpenAI HTTP embedding 入库后退出，再重开 SQLite 完成异步 RAG；writer 拒绝覆盖已有示例文件。驱动的 native 提取目录由构建设置为外置盘 `jdk8/native-tmp`，应用自行运行时也可用 `-Dorg.sqlite.tmpdir=/Volumes/Nas/...` 指定已存在目录。

第六阶段结果：122 个 Java 8 验收、627 个 JDK 17 选定回归、62 个 Publisher TCK 通过；14 项官方 untested 单独保留。驱动和 JNI 已在 macOS/aarch64 的 Java 8 验证，其他平台、网络文件系统锁、多进程并发写和断电恢复尚未验收；见 `verification/milestone-6.json`。

## SQLite 会话记忆

同模块新增 `SqliteChatMemoryStore`，可与向量存储共用一个文件。将它传给 `MessageWindowChatMemory.builder().chatMemoryStore(store)`，再配置 AI Services 的 `chatMemory` 或 `chatMemoryProvider`；完整示例见 SQLite 模块说明。默认同步；异步/响应式记忆需显式 `.asyncExecutor(callerOwnedExecutor)`，应用负责关闭该 Executor。

支持 String/Integer/Long/UUID 类型隔离的会话 ID，消息使用 core JSON codec 保存，含工具调用和结果；更新原子替换整段会话，删除只影响对应 ID。`evictChatMemory` 只清理实例缓存；删除持久化历史需 `ChatMemory.clear()` 或 `deleteMessages(id)`。同会话并发 turn 仍需应用串行化，取消也不会回滚已经提交的消息。

独立 BOM 示例 `memory-write/read <file>` 由脚本在两个 Java 8 进程运行：先保存 4 条含自动工具调用/结果的消息，再恢复历史进入 5 条模型请求，最终保存为 6 条。第七阶段为 149 个 Java 8 / 692 个 JDK 17 / 62 个 TCK 通过，14 项官方 untested 保留；见 `verification/milestone-7.json`。Guardrails 继续跳过。
