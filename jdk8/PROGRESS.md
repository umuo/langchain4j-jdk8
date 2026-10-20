# Java 8 移植进度

## 当前状态：第三阶段基础 RAG 验收进行中（WIP，2026-10-20）

- 仓库：`/Volumes/Nas/codex-work/langchain4j-jdk8`；分支 `jdk8/main`；当前完成标记 `jdk8-m2`，首期标记 `jdk8-m1`。
- 固定上游基线：`7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。相邻原始仓库未修改；完整 Git 历史独立保存，不共享对象文件。
- Maven 坐标：`dev.langchain4j.jdk8:*:1.23.0-jdk8-SNAPSHOT`，兼容 BOM 现覆盖六个生产模块。已安装到外置盘 Maven 缓存；未远程推送或公共发布。

## 已完成能力

1. 首期五模块：core、HTTP 抽象、OkHttp、reactive-streaming、OpenAI 的 Java 8 源码与 Chat Completions 闭环。
2. 第二阶段加入 `langchain4j` 主模块：AI Services 接口代理、继承/非 public 接口的默认方法、可变参数/空数组/null、原始异常、注解提示词和可替换元数据 SPI。
3. 结构化 POJO 输出、Future、TokenStream 和 Reactive Streams Publisher；同步/异步会话隔离，以及 ChatMemoryAccess 查询与清理。
4. 自动工具循环：同步、Future、回调 SSE、响应式 SSE 四条路径，真实 loopback HTTP 联动，参数/结果回传、并发工具、失败和轮次限制。
5. Future/Publisher 取消后 HTTP 调用释放；内部取消专用回调与正常完成/失败区分。Java 17 下仍可反射读取 record 元数据。

## 最终验证

执行 `./jdk8/build.sh`，完整 clean install、独立 BOM 消费项目、字节码审计、真实 Java 8 类链接和 AI Services 模拟对话全部通过。

| 验证 | 第二阶段结果 |
|---|---|
| 六个模块生产源码 | JDK 17 `--release 8` 构建通过 |
| 真实 Java 8 验收 | 51 个用例，0 失败/错误/跳过；JDK 1.8.0_462 |
| 选定上游及现代反射回归 | 435 个用例，0 失败/错误/跳过；JDK 17.0.13 |
| 字节码和禁止引用审计 | 28 个 JAR 文件、5840 个基础类通过；含项目构建/安装副本 |
| Java 8 类及成员类型加载 | 4620 个类通过；3 个可选 Android/Conscrypt/OSGi 入口排除 |
| 独立 BOM 消费示例 | Java 8 通过 AI Services 返回 `Java 8 OK` |
| 模型资源检查 | 兼容 JAR 只包含配套 1.9.3 模型，未打入 2.5.0 模型 |
| diff/shell 检查 | `git diff --check`、`sh -n jdk8/build.sh` 通过 |

结果快照：`verification/milestone-2.json`；首期快照保留为 `verification/milestone-1.json`（25 个 Java 8 / 228 个上游用例）。当前详细 JUnit 报告位于两个测试模块的 `target/surefire-reports`。这些是选定回归，不代表上游全量/Kotlin 测试已运行。

本阶段上游回归新增 AI Services/Builder、元数据工厂、会话记忆/存储、POJO/枚举/多态/通用输出解析、句子分割、工具参数转换和 Locale 行为；工具夹具按原始 arg0/arg1 参数约定编译，生产参数名保留不变。

## 已知边界

- Java 8 的 Flow 签名替换、record/sealed 降级和线程模型差异继续适用；具体约定见 `COMPATIBILITY.md`。
- OpenNLP 2.x 不能运行于 Java 8。使用 1.9.5 和官方 0.35.0 历史中的 1.9.3 句子模型，未修改模型版本绕过校验；Java 8 和 6 个原始分割用例通过。1.9.x 已结束维护，模型结果可能与新版不同，来源和生命周期限制已记录。
- 完整高层 RAG、Guardrails、Decision Services、复杂补偿事务及第三方适配器尚未全量业务验收。OpenAI Responses/音视频/图像/真实 embedding 仍沿用首期未覆盖声明。
- Reactive Streams 官方完整 TCK 尚未运行；类链接检查排除需要额外宿主平台的三个可选入口，不是对所有方法体的穷尽证明。

## 下一阶段：基础 RAG 完整流程

1. 使用确定性的本地 embedding/mock 模型，打通文档 -> 分段 -> 向量存储 -> 检索 -> AI Services 提示词注入 -> 答复。
2. 验证分段元数据、检索过滤、空结果、多文档、持久化/恢复及加载失败边界；同步/异步与流式路径分层推进。
3. 增加 Java 8 端到端示例和选定上游 RAG 回归，继续使用相同 BOM、审计和 Git 检查点约定。
4. 此后按需求扩展 MCP、Agentic、具体向量库和其余 OpenAI API；未自动跟随上游或建立后台任务。

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
| `jdk8-m2` | 第二阶段最终构建、486 个分层用例、产物消费与运行审计完成 |

下一轮先读此文件、`COMPATIBILITY.md` 和 `git status`。一次性转换工具只用于原始快照，不能对已移植源码重跑作为升级方案。

## 第三阶段检查点（WIP）

- 已从干净的 `jdk8-m2` 恢复；外置盘挂载，约 69 GiB 可用。
- 不新增集成模块，沿用六模块兼容构建；增加确定性本地 RAG 全链路和选定上游回归。
- 本阶段尚未验收，当前已支持范围仍以第二阶段结果为准。
