# Java 8 MCP 工具客户端

兼容坐标 `dev.langchain4j.jdk8:langchain4j-mcp:1.23.0-jdk8-SNAPSHOT`，由兼容 BOM 管理。
固定源码基线与其他模块一致；生产代码 release 8，没有新增依赖版本，复用 Jackson 2、OkHttp 4.12.0。

```java
try (DefaultMcpClient client = DefaultMcpClient.builder()
        .transport(StreamableHttpMcpTransport.builder()
                .url("http://127.0.0.1:8080/mcp")
                .timeout(Duration.ofSeconds(30))
                .customHeaders(Collections.singletonMap("Authorization", "Bearer explicit-token"))
                .build())
        .autoHealthCheck(false)
        .toolExecutionTimeout(Duration.ofSeconds(20))
        .build()) {
    McpToolProvider tools = McpToolProvider.builder().mcpClients(client).build();
    Assistant assistant = AiServices.builder(Assistant.class)
            .chatModel(model).toolProvider(tools).build();
    System.out.println(assistant.chat("question"));
}
```

示例中的地址、鉴权和模型由调用方配置；默认测试全部使用 loopback 模拟服务。
独立 BOM 的可运行示例为 `jdk8/example/src/main/java/McpExample.java`，由 `jdk8/build.sh` 在真实 Java 8 下执行。

## 本轮已验收

- MCP **2025-11-25 工具子集**：initialize/initialized、版本确认、会话头和协议头、tools/list 分页/缓存及 schema、tools/call 同步/原生异步、工具结果和错误。
- HTTP JSON 与连续 POST SSE（UTF-8 分片、多行 data、CRLF、通知）；HTTP/RPC/应用错误、缺少匹配 ID、损坏 JSON 和提前 EOF。
- McpToolProvider/McpToolExecutor 接入 AI Services 同步、Future、TokenStream、Publisher 四条 loopback OpenAI 工具循环；结果正确回传模型。
- 直接 MCP Future（含 McpToolExecutor Future）的取消通知及 HTTP 释放、工具超时、close、并发请求 ID 隔离。初始化失败自动关闭传输；所有终止路径清理 pending registry。
- record/sealed 转为 Java 8 类型，保留访问器/值语义/集合复制与 JSON；选定上游协议/结果/工具/schema 回归独立运行于 JDK 17。

## 有意差异及未覆盖能力

- HTTP 传输使用 OkHttp，默认协议版本固定为 2025-11-25，关闭自动 modern 协议探测；HTTP 返回其他版本时明确失败。不是完整 MCP 规范实现或所有上游功能已支持的声明。
- 新增 builder `httpClient(OkHttpClient)`。调用方拥有该客户端的 executor/pool；transport.close 只中止自己登记的请求。默认创建的客户端由 transport 关闭。自定义客户端时，TLS、超时、重定向和线程调度采用该客户端配置；原 builder `sslContext`/`executor` 未保留，应在 OkHttpClient.Builder 中配置对应能力（TLS 需同时提供匹配的 trust manager）。
- `timeout` 为默认 OkHttp 的 connect/read/call timeout，客户端 `toolExecutionTimeout` 为工具等待时限；保留上游把工具超时转换为模型可见错误文本的行为。SSE 读取占用 OkHttp dispatcher 线程，不宣称事件循环式非阻塞读取。
- **AI Services 取消保持上游 drain 语义**：阻止后续模型轮次，但已启动工具执行到完成/工具超时，以保留补偿结果；不会立即取消该工具的 MCP Future。直接调用 MCP/ToolExecutor Future 的 cancel 则发送 notifications/cancelled 并关闭 HTTP。取消通知为尽力而为，不能保证服务器回滚已执行的副作用。
- stdio、WebSocket、Registry 源码保留，未进入本兼容 JAR；没有启动外部进程或连接真实服务器。
- resources/prompts/roots/logging/progress/多模态结果与 modern protocol 的协议 DTO/客户端代码可随模块编译，部分转换有上游回归，但没有这些业务路径的端到端支持声明。
- GET subsidiary SSE、Last-Event-ID/重连/恢复订阅、2026 modern 协议、旧 HTTP+SSE 双端点、会话迁移、404 自动重新初始化和 DELETE 结束会话未实现；`subsidiaryChannel(true)` 明确失败。会话过期会返回 HTTP 错误，调用方需关闭并重新创建客户端。
- 工具请求不自动重放，避免重复副作用。关闭连接、EOF 和 503 不被解释为服务器未执行工具；由应用决定后续恢复操作。
- 未验证远程 MCP 服务、TLS/OAuth 自动授权、代理、跨域重定向和服务器行为差异；日志默认关闭，显式启用会包含请求/响应内容。

协议依据：[传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)。这些链接描述完整协议，本移植的范围以本文件为准。
