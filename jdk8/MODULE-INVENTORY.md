# 固定基线模块清单

基线 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`；枚举全部 120 个 POM。依赖原始声明和源码证据见 [MODULE-INVENTORY.json](MODULE-INVENTORY.json)。

分类由生产源码路径提供候选证据，`other-review` 仍须逐项人工核对，不能据此排除模块。状态 pending 不代表已经实现或已证明不兼容。生产依赖尚须解析父 POM、属性和传递依赖后验证。

| 模块 | 分类 | 状态 |
|---|---|---|
| `code-execution-engines/langchain4j-code-execution-engine-azure-acads` | other-review | pending |
| `code-execution-engines/langchain4j-code-execution-engine-graalvm-polyglot` | other-review | pending |
| `code-execution-engines/langchain4j-code-execution-engine-judge0` | other-review | pending |
| `document-loaders/langchain4j-document-loader-amazon-s3` | other-review | pending |
| `document-loaders/langchain4j-document-loader-azure-storage-blob` | other-review | pending |
| `document-loaders/langchain4j-document-loader-github` | other-review | pending |
| `document-loaders/langchain4j-document-loader-google-cloud-storage` | other-review | pending |
| `document-loaders/langchain4j-document-loader-playwright` | other-review | pending |
| `document-loaders/langchain4j-document-loader-selenium` | other-review | pending |
| `document-loaders/langchain4j-document-loader-tencent-cos` | other-review | pending |
| `document-parsers/langchain4j-document-parser-apache-pdfbox` | other-review | pending |
| `document-parsers/langchain4j-document-parser-apache-poi` | other-review | pending |
| `document-parsers/langchain4j-document-parser-apache-tika` | other-review | pending |
| `document-parsers/langchain4j-document-parser-docling` | other-review | pending |
| `document-parsers/langchain4j-document-parser-markdown` | other-review | pending |
| `document-parsers/langchain4j-document-parser-yaml` | other-review | pending |
| `document-transformers/langchain4j-document-transformer-jsoup` | other-review | pending |
| `embedding-store-filter-parsers/langchain4j-embedding-store-filter-parser-sql` | other-review | pending |
| `embeddings/langchain4j-embeddings-all-minilm-l6-v2-q` | model | pending |
| `embeddings/langchain4j-embeddings-all-minilm-l6-v2` | model | pending |
| `embeddings/langchain4j-embeddings-bge-small-en-q` | model | pending |
| `embeddings/langchain4j-embeddings-bge-small-en-v15-q` | model | pending |
| `embeddings/langchain4j-embeddings-bge-small-en-v15` | model | pending |
| `embeddings/langchain4j-embeddings-bge-small-en` | model | pending |
| `embeddings/langchain4j-embeddings-bge-small-zh-v15-q` | model | pending |
| `embeddings/langchain4j-embeddings-bge-small-zh-v15` | model | pending |
| `embeddings/langchain4j-embeddings-e5-small-v2-q` | model | pending |
| `embeddings/langchain4j-embeddings-e5-small-v2` | model | pending |
| `embeddings/langchain4j-embeddings` | model | pending |
| `experimental/langchain4j-experimental-hibernate` | other-review | pending |
| `experimental/langchain4j-experimental-skills-shell` | other-review | pending |
| `experimental/langchain4j-experimental-sql` | other-review | pending |
| `http-clients/langchain4j-http-client-apache` | other-review | pending |
| `http-clients/langchain4j-http-client-jdk` | other-review | pending |
| `http-clients/langchain4j-http-client-okhttp` | other-review | partial-verified-m9 |
| `integration-tests/integration-tests-class-instance-loader/integration-tests-class-instance-loader-quarkus` | other-review | pending |
| `integration-tests/integration-tests-class-instance-loader/integration-tests-class-instance-loader-spring` | other-review | pending |
| `integration-tests/integration-tests-class-instance-loader` | other-review | pending |
| `integration-tests/integration-tests-class-metadata-provider/integration-tests-class-metadata-provider-spring` | other-review | pending |
| `integration-tests/integration-tests-class-metadata-provider` | other-review | pending |
| `integration-tests/integration-tests-decision-models` | other-review | pending |
| `integration-tests/integration-tests-guardrails` | other-review | pending |
| `integration-tests/integration-tests-jackson3` | other-review | excluded |
| `integration-tests` | other-review | pending |
| `internal/langchain4j-docu-chatbot-updater` | other-review | pending |
| `internal/langchain4j-internal-test-retry` | other-review | pending |
| `langchain4j-agentic-a2a` | agentic | pending |
| `langchain4j-agentic-mcp` | agentic | pending |
| `langchain4j-agentic-patterns` | agentic | pending |
| `langchain4j-agentic` | agentic | WIP（基础移植，完整验收未完成） |
| `langchain4j-anthropic` | model | pending |
| `langchain4j-azure-ai-search` | vector-store | pending |
| `langchain4j-azure-cosmos-mongo-vcore` | vector-store | pending |
| `langchain4j-azure-cosmos-nosql` | vector-store | pending |
| `langchain4j-azure-documentdb` | vector-store | pending |
| `langchain4j-azure-open-ai` | model | pending |
| `langchain4j-bedrock` | model | pending |
| `langchain4j-bom` | other-review | pending |
| `langchain4j-cassandra` | vector-store | pending |
| `langchain4j-chroma` | vector-store | pending |
| `langchain4j-cohere` | model | pending |
| `langchain4j-coherence` | vector-store | pending |
| `langchain4j-core-jackson3` | other-review | excluded |
| `langchain4j-core` | model, vector-store | partial-verified-m9 |
| `langchain4j-couchbase` | vector-store | pending |
| `langchain4j-easy-rag` | other-review | pending |
| `langchain4j-elasticsearch` | vector-store | pending |
| `langchain4j-google-ai-gemini` | model | pending |
| `langchain4j-google-genai` | model | pending |
| `langchain4j-gpu-llama3` | model | pending |
| `langchain4j-guardrails` | other-review | pending |
| `langchain4j-hibernate` | vector-store | pending |
| `langchain4j-http-client` | other-review | partial-verified-m9 |
| `langchain4j-hugging-face` | model | pending |
| `langchain4j-infinispan` | vector-store | pending |
| `langchain4j-jackson3` | other-review | excluded |
| `langchain4j-jina` | model | pending |
| `langchain4j-jitllm` | model | pending |
| `langchain4j-jlama` | model | pending |
| `langchain4j-kotlin` | other-review | pending |
| `langchain4j-local-ai` | model | pending |
| `langchain4j-mariadb` | vector-store | pending |
| `langchain4j-mcp-docker` | other-review | pending |
| `langchain4j-mcp` | mcp | partial-verified-m14 |
| `langchain4j-micrometer-metrics` | other-review | pending |
| `langchain4j-milvus-v2` | vector-store | pending |
| `langchain4j-milvus` | vector-store | pending |
| `langchain4j-mistral-ai` | model | pending |
| `langchain4j-mongodb-atlas` | vector-store | pending |
| `langchain4j-nomic` | model | pending |
| `langchain4j-observation` | other-review | pending |
| `langchain4j-ollama` | model | pending |
| `langchain4j-onnx-scoring` | model | pending |
| `langchain4j-open-ai-official` | model | pending |
| `langchain4j-open-ai` | model | partial-verified-m9 |
| `langchain4j-opensearch` | vector-store | pending |
| `langchain4j-oracle` | model, vector-store | pending |
| `langchain4j-ovh-ai` | model | pending |
| `langchain4j-parent` | other-review | pending |
| `langchain4j-pgvector` | vector-store | pending |
| `langchain4j-pinecone` | vector-store | pending |
| `langchain4j-qdrant` | vector-store | pending |
| `langchain4j-reactive-streaming` | other-review | partial-verified-m9 |
| `langchain4j-skills` | other-review | pending |
| `langchain4j-tablestore` | vector-store | pending |
| `langchain4j-test` | other-review | pending |
| `langchain4j-typesafe` | model | pending |
| `langchain4j-vertex-ai-anthropic` | model | pending |
| `langchain4j-vertex-ai-gemini` | model | pending |
| `langchain4j-vertex-ai` | model | pending |
| `langchain4j-vespa` | vector-store | pending |
| `langchain4j-voyage-ai` | model | pending |
| `langchain4j-watsonx` | model | pending |
| `langchain4j-weaviate` | vector-store | pending |
| `langchain4j-workers-ai` | model | pending |
| `langchain4j` | vector-store | partial-verified-m9 |
| `.` | other-review | pending |
| `web-search-engines/langchain4j-web-search-engine-google-custom` | other-review | pending |
| `web-search-engines/langchain4j-web-search-engine-searchapi` | other-review | pending |
| `web-search-engines/langchain4j-web-search-engine-tavily` | other-review | pending |


- 2026-10-21 Agentic async 取消 WIP：main 161 源文件/216 类，patterns 15 基线文件/22 类；完整 clean 共 1711 通过/14 官方 TCK untested。失败 root 按调用登记中断 async 并守卫 scope 方法写入，5 个真实 Java 8 子进程证明取消、输出覆盖、跨 ID 隔离与外部 marker 所有权；快照 verification/agentic-cancellation-wip.json。网络流、同步并行失败收尾、全部四模块完整能力/BOM 与既有其他长期范围仍 pending。

- 2026-10-21 同步并行失败收尾 WIP：新增 3 个真实 Java 8 子进程覆盖默认/自定义执行器 fail-fast 与派发拒绝后已提交任务取消；主 Agentic 92 项、完整 clean 1714 通过/14 官方 TCK untested，API/来源/链接审计通过。快照 verification/agentic-parallel-failure-wip.json。网络流、完整生命周期及其余四模块/BOM/长期范围仍 pending。

- 2026-10-21 中间流网络取消 WIP：7 个实际 Java 8 socket 场景证明默认 OkHttp 在响应头前、首段后及默认工具续传时关闭对应 Call、终止等待者并排空 dispatcher；新增确定性双人工 marker 结算回归和 3 项上下文测试。最终 clean 1725 通过/14 官方 TCK untested，API/来源/字节码/链接及既有 BOM 消费通过，无生产依赖变化。main 162 文件/217 类，patterns 15 基线文件/22 类。快照 verification/agentic-network-cancellation-wip.json；terminal 流、HTTP 回调 scope 归属、异步工具、自定义传输、完整生命周期与其余长期范围仍 pending。

- 2026-10-22 捕获回调 scope 代际归属 WIP：4 项实际 Java 8 定向测试和新增 SSE 迟到工具用例通过；旧代际拒绝三种写入，嵌套/异常/线程复用恢复绑定，传输失败清理仍可进入。完整 clean 1730 通过/14 官方 TCK untested，API/来源/链接/字节码及既有 BOM 消费通过，无生产依赖变化。快照 verification/agentic-callback-scope-wip.json。terminal 流、异步工具、自定义传输、成功收尾与其余长期范围仍 pending。

- 2026-10-22 AI Services executor 上下文组合 WIP：4 项基础 Java 8 验收和新增 2 项 SSE 工具 executor 场景通过；保留用户 SPI 快照/恢复，复制 InvocationContext 后仍带 root owner，拒绝迟到 scope 写入并立即取消工具中新建 HTTP。完整 clean 1736 通过/14 官方 TCK untested，严格 API/来源/产物/链接与既有 BOM 消费通过，无签名/生产依赖变化。快照 verification/agentic-executor-context-wip.json。工具主动中断、排队取消、terminal 流和全部长期范围仍 pending。
