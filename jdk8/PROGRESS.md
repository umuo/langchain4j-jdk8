# Java 8 移植进度

## 当前状态：首期里程碑已完成（2026-10-20）

- 仓库：`/Volumes/Nas/codex-work/langchain4j-jdk8`；分支 `jdk8/main`；完成标记 `jdk8-m1`。
- 固定基线：`7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`；完整历史独立保存，无 alternates/hardlinks；相邻原始仓库无修改。
- 已完成首期：core、HTTP、OkHttp、reactive-streaming、OpenAI 的 Java 8 生产源码移植及 Chat Completions 闭环验收。
- 坐标：`dev.langchain4j.jdk8:*:1.23.0-jdk8-SNAPSHOT`，包含兼容 BOM；已安装到外置盘本地 Maven 仓库，未远程推送或公共发布。

## 最终验证

执行 `./jdk8/build.sh`，完成 clean install、独立 BOM 项目构建、字节码审计、真实 Java 8 类链接和模拟对话。结果：

| 验证 | 结果 |
|---|---|
| 全部五个模块生产源码 | JDK 17 `--release 8` 构建通过 |
| Java 8 独立验收 | 25 个用例通过，0 失败/错误/跳过；JDK 1.8.0_462 |
| 上游选定回归 | 228 个用例通过，0 失败/错误/跳过；JDK 17.0.13 |
| JAR 字节码与禁止引用审计 | 25 个文件、4514 个基础类通过；含项目构建/安装副本 |
| Java 8 类与成员类型加载 | 3537 个类通过，2 个 OkHttp 可选 Android/Conscrypt 平台类排除 |
| 独立 BOM 消费示例 | Java 8 返回 `Java 8 OK` |
| `git diff --check` 与 shell 语法检查 | 通过 |

可审阅的测试结果快照：`verification/milestone-1.json`。最新完整测试报告在 `acceptance/target/surefire-reports` 和 `upstream-tests/target/surefire-reports`，构建输出不提交。

Java 8 验收覆盖集合/record 值与 JSON 语义、平台线程回退、SSE 的 UTF-8 分片、背压/重入/并发/溢出/单次终止，以及 OpenAI 普通/异步/两种流式调用、请求结构、工具消息、结构化格式、错误/超时和连接释放。

上游回归保留原始测试源，选择 Utils、ValidationUtils、Metadata、AiMessage、ToolExecutionRequest、JsonParsingUtils、JsonSchemaElementUtils、BatchItemResult、DecisionResponse、DefaultServerSentEventParser。并非上游全量测试。

## 后续阶段

1. 下一阶段移植 `langchain4j` 的 AI Services：元数据加载、Java 8 默认接口方法代理、结构化 POJO 输出和异步接口。
2. 接入会话记忆与自动工具执行循环；验证多轮调用、失败和取消，另设提交及验收标记。
3. 接入基础 RAG 完整流程；再按实际需要扩展 MCP、Agentic、向量库及其他模型。
4. 首期未业务验收的 Responses/音视频/图像/Embedding API 逐项补齐；响应式桥接后续增加官方 TCK 验证。

接口差异、未覆盖能力和已知警告以 `COMPATIBILITY.md` 为准。开始下一轮时先读此文件和 `git status`，不要直接重跑一次性迁移工具。

## 检查点历史

- `39997fc70`：独立工程、构建入口和文档初始化（WIP）。
- `24cb6b2b4`：core 降级与兼容工具（WIP）。
- `5393c71b0`：HTTP/SSE/OkHttp 移植（WIP）。
- `d4a1f573e`：响应式桥接接入（WIP）。
- `2011061b1`：OpenAI 模型客户端与 loopback 验收（WIP）。
- `jdk8-m1`：记录类 JSON、响应式边界修复，双 JDK 回归、产物审计和独立消费验收完成。
