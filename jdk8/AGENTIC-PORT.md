# Agentic 主模块基础移植（WIP）

这是第十五阶段的可检查增量，尚未完成 Agentic 的全部验收。最新结果见 [恢复与单元回归快照](verification/agentic-recovery-wip.json)，初始基础检查点见 [agentic-main-wip.json](verification/agentic-main-wip.json)。上一完整阶段标签为 `jdk8-m14`；默认八个支持模块和兼容 BOM 不变。

## 已有实现与证据

- `langchain4j-agentic` 的 158 个固定基线生产 Java 文件全部保留，新增 `internal/AgenticJdk8.java`；主模块整体编译为 release 8，不通过排除困难文件换取编译。
- 主模块 25 个 record、模式匹配、switch expression、文本块、var、匿名 diamond 及高版本 API 转为 Java 8。保留规范构造器、组件访问器、对象相等性、自动值方法 final 属性、不可变集合与 JSON 构造信息。
- 类型推断仅在固定 Git 对象导出的临时副本上执行，javac 17 attribution errors=0；继而在临时副本降低语法，只复制有语义变化的源码，再人工修复/验证。旧迁移工具未作用于已改造 core/main 或重新作用于当前 Agentic 源码。
- 真实 Java 8 的 `AgenticFoundationJdk8Test` 共 14 项，0 失败/错误/跳过。包含 stub 模型 Agent、顺序/条件/循环/并行、参数类型转换、错误恢复与缺参、状态与 deferred 响应 JSON 恢复、消息变换与 UTF-8 报告；不代表其他复杂 Agentic 行为已验收。
- API 对照脚本从固定 Git 重新导出全部 158 个生产文件并编译为 Java 17 对照产物，比较候选 JAR 的公开/受保护声明、继承成员、泛型与 bridge；基线包含内部包。首次检查发现生成 equals/hashCode/toString 缺少 final，已经修正。Record 父类/反射元数据替换为 POJO 是明确差异。
- 有效生产依赖树用 Maven dependency plugin 3.8.1 的 runtime tree 生成，见结果快照。兼容 main/core 和全部外部依赖均为已验证模块/库，无官方 SNAPSHOT、新模型 SDK 或额外 JSON 库。原始上游模型测试依赖保留于 Git 基线，不进入兼容生产 POM；本阶段没有声称运行全部原始 Agentic 测试。

## 可复现验证

外置盘需挂载，运行时和 Maven 路径沿用项目约定。

```sh
# 保留所有现有回归、TCK 和独立消费，并额外构建/测试 WIP 主模块。
./jdk8/build.sh -o -Pagentic-wip

# 固定 Git 基线与刚构建候选 JAR 的 API 对照；无网络，不修改生产源码。
./jdk8/audit-agentic-api.sh
```

聚焦检查可以使用兼容父 POM（Maven 运行于 JDK 17，java8.home 指向实际 JDK 8）：

```sh
mvn -o -B -s /Volumes/Nas/envs/maven/settings.xml \
  -Dmaven.repo.local=/Volumes/Nas/envs/maven/repository \
  -Djava8.home=/Users/gitsilence/.sdkman/candidates/java/8.0.462-zulu \
  -f jdk8/pom.xml -Pagentic-wip -pl :langchain4j-agentic -am \
  clean package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree \
  -Dscope=runtime -DoutputType=json -DoutputFile=target/runtime-dependencies.json
```

本次 clean 全流程包含原 Java 8 346 项、新增 14 项、选定上游 864 项、TCK 62 通过（14 官方 untested）；原 BOM 示例均通过。API 对照无成员遗漏/改变或额外接口成员；新增 JAR 208 类，实际 Java 8 扩展链接 5154 类。

测试运行时版本、API 计数、字节码和链接专项结果见 [agentic-main-wip.json](verification/agentic-main-wip.json)。正式八模块审计和消费示例仍原样运行；额外 WIP JAR 单独做 Java 8 字节码及链接验证，尚不是 BOM 消费证明。

## 仍需完成

- 主模块剩余 IT/端到端回归，以及模型 HTTP/SSE、异步/流式 Agent、会话/记忆、取消、超时、关闭/线程和补偿的完整闭环。
- Human-in-the-loop、挂起/恢复/持久化、planner/supervisor 的全部公开策略及错误路径；声明式配置、监控/listener/HTML 等完整能力。
- patterns 的所有模式、Agentic MCP 的 schema/代理和完整 A2A 任务/租户/上下文/流式能力及其 SDK 依赖。
- 各模块的兼容 BOM、独立消费、全量产物审计和完整阶段验收。完成这些工作前不创建 `jdk8-m15` 标签或标记 Agentic 已支持。
- MCP 剩余协议与能力、模型/向量库等整体目标继续保留，排除项仍只有用户指定范围。


## 第二个 WIP 单元：全部原始单元回归与真实 Java 8 恢复

- 新增独立 `agentic-upstream-tests` harness，由 `agentic-wip` profile 选择，运行于 JDK 17。固定基线的全部 51 个 `*Test.java` 原始源文件及断言保持不变；包括恢复、supervisor/HITL、补偿、async/streaming、声明式、参数、注册表和监控等单元回归。它不等于运行了全部外部服务 IT。
- 通过 `./jdk8/audit-agentic-tests.sh` 检查 51 文件选取及原始内容；共享流式夹具仅替换两处 Flow 导入，Invoice 夹具的三个嵌套声明与固定 Git AST 完全一致。没有加载 IT 的 Models 配置，完整 provenance 见 harness README。
- 新增实际 Java 8 的 supervisor 挂起/恢复：待人工输入时只规划一次，完成响应后再规划两次，LAST 返回最后子 Agent 结果。首次新增断言误把 LAST 期望成 done 的 response；按基线枚举文档修正为最后子 Agent 输出，未改生产行为或删除断言。
- 新增两个独立真实 Java 8 进程：writer 执行 prepare/ask 后将挂起 scope/checkpoint 存文件并退出；reader 新建工作流、加载并完成响应，继续 finish。文件执行日志严格为 prepare/ask/finish，证明该场景不重放已完成步骤。包含 Unicode、待响应 ID 去重和完成后执行状态清理。
- 文件 store 是单 writer、String memory ID 的测试夹具，writer 为挂起后正常退出；不宣称通用生产存储、断电/强杀原子性或多进程并发写已验证。与现有真实 Java 8 14 项基础场景合计 16 项。
- 当前功能证明仍不足以标记完整 Agentic 支持。后续继续真实 HTTP/SSE、async/streaming、记忆/补偿/取消/关闭完整场景及其余三个模块的全部工作。


本单元完整 clean 验证通过：真实 Java 8 共 362 项（支持模块 346 + Agentic 16），选定 JDK 17 共 1115 项（既有 864 + 全部 Agentic 单元 251），TCK 62 通过、14 官方 untested；共 1539 通过。API/来源/字节码/实际 Java 8 链接审计及既有独立 BOM 示例通过，尚未新增 Agentic BOM 消费。
