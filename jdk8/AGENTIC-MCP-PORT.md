# Agentic MCP Java 8 回移植（WIP）

固定上游基线 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。全部六个生产文件进入 `agentic-wip` reactor，以 JDK 17 `--release 8` 编译。两个私有 record 改为值类，保留访问器、对象相等性及 ParameterizedType 的参数数组防御拷贝；集合工厂、空白判断、Optional、Stream、新式 switch 和模式匹配改为 Java 8 实现。包名与公开签名保留。

这是一组基础集成检查点，未将全部 Agentic 宣布为支持，未纳入默认支持 BOM。上次完整标签仍为 `jdk8-m14`。主 Agentic、patterns、A2A、MCP 剩余协议及模型/向量库目标继续推进。

## 构建和消费

```sh
./jdk8/build.sh -o -Pagentic-wip
```

该命令运行原有全部检查，并额外构建本模块、两份原始上游测试、实验 BOM 和独立消费项目。独立消费验证入口为 [verify-agentic-mcp.sh](verify-agentic-mcp.sh)；先有完整 reactor 的本地 install 产物，再执行此脚本。所有缓存、临时源码及输出均位于外置盘。

实验 BOM 坐标为 `dev.langchain4j.jdk8:langchain4j-agentic-wip-bom:1.23.0-jdk8-SNAPSHOT`，导入既有兼容 BOM，额外管理 main Agentic、patterns、agentic-mcp 三个 WIP 产物。版本管理不等于完整功能支持；默认 `langchain4j-bom` 保持原有八模块范围。未推送或发布。

[独立 POM](agentic-mcp-example/pom.xml) 没有 reactor 父项目，使用安装的 BOM/JAR；[示例](agentic-mcp-example/src/main/java/example/AgenticMcpExample.java) 在实际 Java 8 中验证 SPI 声明式异步 HTTP、scope 结果、代理身份方法、真实 stdio 子进程、patterns 策略及借用执行器生命周期。stdio 的阻塞 reader/stderr 使用单独的借用 I/O 池，避免占满有限 agent worker；应用负责最后关闭两者。

## 已验证证据

| 检查 | 结果与范围 |
|---|---|
| 生产编译与依赖 | 全部 6 文件；9 个 Java 8 class（含 1 个合成访问标记）；仅依赖兼容 Agentic 与 MCP，未增加生产库或官方 SNAPSHOT |
| Java 8 验收 | 15 项：typed/untyped、声明式 SPI、异步工作流、结构化 POJO、参数 schema/元数据/不可变性、activation、代理身份、应用/RPC 错误、超时、root 失败取消、8 会话并发、分段 UTF-8 SSE、真实 stdio |
| 原始测试 | 固定基线全部 2 个非 IT Test 类原样运行于 JDK 17，共 29 项；外部 JBang/模型 IT 未运行，不伪造为通过 |
| API 与来源 | [审计脚本](audit-agentic-mcp-api.sh) 从固定 Git 源码编译 Java 17 参考；5 可见类型、54 声明、131 继承公开条目无缺失或增项；2 原始 Test 与 SPI 描述文件逐字节保持 |
| 完整 clean | Java 8 功能 686 项（431 + 211 + 29 + 本模块 15）；JDK 17 1214 项；TCK 62 通过/14 官方 untested，共 1962 通过 |
| 默认交付 | 33 JAR/6390 基础类条目；4958 类默认链接；原有全部 BOM 消费通过 |
| 实验交付 | 独立消费 23 JAR/4792 类，链接 4789 类；默认加三个 WIP JAR 链接 5209 类；仍仅排除既有 3 个可选 Android/Conscrypt/OSGi 入口 |

快照：[agentic-mcp-foundation-wip.json](verification/agentic-mcp-foundation-wip.json)。原有 main/patterns API、两个兼容 helper ABI 与原始测试来源也重新审计通过。

## 行为与验证边界

- `async(true)` 维持基线的 Agentic 子调用调度语义：包装器内部调用同步 `McpClient.executeTool`，不把直接返回 String 的接口伪装为原生 nonblocking API。取消已启动的框架 worker 时，中断沿既有 MCP 实现到达请求与物理连接。
- timeout/root 失败用例在 fixture cleanup 之前断言一次工具请求、一次取消通知、服务端写入失败和 dispatcher 排空，之后同一借用 client 仍能继续调用；未以自然 EOF 或关闭测试服务器代替取消证明。取消不能撤销远端已发生的副作用。
- 首轮有 13 项通过、1 项代理缺陷和1项夹具消息假设失败。`equals` 原先误进工具参数映射；现 equals/hashCode/toString 在本地处理，验证三者不发送工具请求。这是有意的行为修复，无公开签名变化。真实 MCP 应用错误在低层已抛 McpApplicationErrorException，夹具改为严格检查该类型和服务端原始文本，未修改原始上游断言或吞掉失败。
- 库借用 MCP client 与用户 executor，不因某个 root 失败而关闭它们；示例与测试由调用方完成最终关闭。没有自动重放工具、自动持久化 client 或新增 checkpoint 字段。
- 当前新增联动证明是 HTTP/SSE 与 stdio。本模块与 WebSocket/现代通道组合、复杂 supervisor/循环/并行/流式复合场景、恢复与补偿及自定义 client/provider 的全部生命周期仍需专项验收；已编译六文件及既有底层传输测试不代替这些场景。
- A2A 尚待实现，main/patterns 的完整能力和最终支持 BOM/消费者交付继续 pending。Guardrails、Spring Boot Starter、Jackson 3 维持排除。
