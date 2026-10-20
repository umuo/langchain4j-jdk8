# Java 8 MCP 工具客户端

兼容坐标 `dev.langchain4j.jdk8:langchain4j-mcp:1.23.0-jdk8-SNAPSHOT`，由兼容 BOM 管理。
固定源码基线与其他模块一致；生产代码 release 8，复用 Jackson 2、OkHttp 4.12.0，无新增依赖版本。
范围为 MCP **2025-11-25 HTTP 工具及会话/SSE 恢复**，不是完整 MCP 规范或全部上游功能支持声明。

```java
StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
        .url("http://127.0.0.1:8080/mcp")
        .timeout(Duration.ofSeconds(30))
        .subsidiaryChannel(true)                // 可选 GET SSE，默认 false
        .reconnectInterval(Duration.ofSeconds(1))
        .maxReconnectAttempts(5)
        .closeTimeout(Duration.ofSeconds(3))
        .customHeaders(Collections.singletonMap("Authorization", "Bearer explicit-token"))
        .build();
try (DefaultMcpClient client = DefaultMcpClient.builder()
        .transport(transport).autoHealthCheck(false)
        .toolExecutionTimeout(Duration.ofSeconds(20)).build()) {
    McpToolProvider tools = McpToolProvider.builder().mcpClients(client).build();
    Assistant assistant = AiServices.builder(Assistant.class)
            .chatModel(model).toolProvider(tools).build();
    System.out.println(assistant.chat("question"));
}
```

地址、鉴权和模型由调用方配置；默认测试全部使用 loopback 服务。独立 BOM 的
`jdk8/example/src/main/java/McpExample.java` 提供普通模式和 `recovery` 模式，均由 `jdk8/build.sh` 在真实 Java 8 执行。
恢复模式验证：旧会话 POST 404 明确失败 → 等待新握手 → 应用发起新的 AI Services 调用 → 工具 SSE 断线 → GET 取回原结果 → DELETE 新会话；不是重试旧工具调用。

## 会话管理

- initialize/initialized 后绑定 session/version；带会话 ID 的 POST 或 GET 收到 404 时，新建会话并共享一次初始化；旧代际在途请求/流明确失败，后续新请求等待新握手。原 POST 不自动重发，也不自动重跑失败的 AI Services 调用。
- 恢复握手使用独立负数 JSON-RPC ID，与默认客户端非负请求 ID 隔离。成功后更新客户端能力，失效缓存；缓存代际阻止旧查询覆盖新数据，跨代际分页列表失败。无会话 ID 的 404 只是 HTTP 错误。
- 握手失败/超时会终止等待者，不无限循环。调用方稍后发起的新请求可重新尝试握手；关闭后不再恢复。`sessionInitialization()` 返回共享握手的观察 Future，取消该观察者不会取消其他请求共享的握手。
- `terminateSession()` 立即停止本地请求、流和重试，异步 DELETE 所有已知会话，返回可观察释放错误的 Future；重复调用共享同一次关闭。删除成功、404（会话已不存在）及 405（服务端不支持）正常完成；其他错误/超时明确失败，本地仍关闭。
- `close()` 执行同一释放并有界等待，失败写日志；需要判断远端释放结果时显式等待 `terminateSession()`。默认 closeTimeout 3 秒。远端已执行的工具副作用、未收到会话 ID 的服务端分配，以及不支持/拒绝 DELETE 的服务器状态无法由客户端保证回滚/清理。

## SSE 与取消

- POST 同时接受 JSON 与 SSE。SSE 支持 UTF-8 分片、BOM、多行 data、CR/LF/CRLF、通知和空 data 的游标预告。只有完整事件提交游标；未结束事件不提交，空 id 重置游标，含 NUL 的 id 忽略。
- POST SSE 断开后，只有具备非空已提交游标才 GET 恢复；所有 GET 都携带当前会话/协议及对应流的 Last-Event-ID。不同工具和后台通道不共享游标。没有游标、损坏 JSON、非法响应类型或重试耗尽时失败，绝不通过 POST 补发请求。
- 可选 GET 子通道默认关闭；开启后接收服务端通知/请求，支持断线后重连，405 正常禁用。子通道禁止承载其他 POST 的响应；恢复请求自己的 GET 可以接收原结果。`subsidiaryChannelCompletion()` 可观察其完成/错误；取消观察 Future 不会关闭通道，关闭 transport 才停止它。
- 默认重试间隔 1 秒、每段连续无进展失败最多 5 次恢复，响应中的有效 retry 毫秒值覆盖间隔（包含 0）；超出 Long 范围的值忽略。收到新事件/新非空游标后重置连续失败计数。后台通道可持续工作；单个工具恢复始终受请求总时限约束。GET 429/5xx/网络断线可重试，其他 HTTP 错误终止，带会话 404 则重建会话。
- transport timeout 默认 60 秒，对每个请求和握手设置总 deadline，覆盖排队/握手等待/全部 SSE 恢复，不因进度重置；也配置默认 HTTP connect/read/call timeout 和 GET 空闲 read timeout。客户端 toolExecutionTimeout 可更短，沿用上游模型可见超时错误文本；显式 HTTP 客户端原有较短超时也可能提前生效。
- 直接 MCP/ToolExecutor Future 取消或超时，会取消活跃 POST/GET 与重试任务，发送绑定原会话的取消通知。还未发送的请求不发送取消通知；旧会话失败不会把取消通知发到新会话。通知是尽力而为，不能保证服务器停止或回滚工具。
- AI Services 保留上游 drain 约定：取消停止后续模型轮次，已启动工具继续到完成/超时以保留补偿结果；不会立即取消其 MCP Future。Guardrails 和通用工具/补偿逻辑未改动。

## HTTP 客户端所有权与行为差异

- builder `httpClient(OkHttpClient)` 可配置 TLS、连接池、POST dispatcher 等；调用方拥有其 executor/pool，transport 不关闭它们。GET 使用 transport 自己的 dispatcher，避免长连接占满 POST 槽位；关闭时停止该 dispatcher 和恢复调度器。默认创建的 POST 客户端及连接池也由 transport 关闭。
- 为避免隐式重放，派生客户端关闭连接重试、重定向、认证器自动跟进，POST body 标为 one-shot，覆盖 OkHttp 的 503 Retry-After:0/421 等跟进逻辑；`followRedirects(true)` 明确拒绝。使用最终 URL 和显式鉴权 headers，供应方自定义 interceptor 不应自行重试工具请求。
- `McpHeadersSupplier` 在无调用上下文的 GET/DELETE 控制请求上收到 null；支持显式 headers 和无参数 Supplier。Session/version/cursor 由 transport 最后设置，不采用供应者的陈旧值。原 builder sslContext/executor 未保留，应在 OkHttp builder 配置 TLS（配套 trust manager）和 dispatcher。
- SSE 阻塞读取占用 dispatcher 线程；这不是事件循环式读取。`setMcpSessionId` 仅允许没有在途请求/流时显式替换外部 ID，不提供跨进程游标迁移。日志默认关闭，开启后包含协议内容。

## 验证及剩余范围

- 31 个新增真实 Java 8 恢复用例，保留此前 24 个 MCP 工具用例：并发失效/共享握手、缓存、新旧会话隔离、GET 通知/405、POST 与 GET 恢复、游标隔离、retry/EOF/UTF-8、取消/超时/重试耗尽、DELETE 成功/404/405/错误/超时、并发 close、所有权、HTTP 503/307/断连不重放。
- stdio/WebSocket/Registry 源码保留但不在兼容 JAR；modern 协议、旧 HTTP+SSE 双端点、完整 resources/prompts/roots/多模态业务、跨进程恢复和真实远程 TLS/OAuth/代理互操作仍未验收。不是通用 SSE 持久化日志或端到端 exactly-once 工具执行保证。
- 完整 clean、独立 BOM 和产物审计结果以 `jdk8/PROGRESS.md` 及阶段快照为准。

协议依据：[MCP 2025-11-25 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)。隐式 HTTP 跟进核对：[OkHttp 4.12.0 源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)。
