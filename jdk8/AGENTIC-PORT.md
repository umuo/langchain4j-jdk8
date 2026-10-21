# Agentic 主模块基础移植（WIP）

这是第十五阶段的可检查增量，尚未完成 Agentic 的全部验收。最新末端挂起检查点见 [挂起恢复快照](verification/agentic-terminal-suspension-wip.json)，此前状态绑定检查点见 [异步状态快照](verification/agentic-deferred-state-wip.json)，此前恢复/补偿检查点见 [错误恢复快照](verification/agentic-error-recovery-wip.json)，此前网络检查点见 [HTTP/SSE 快照](verification/agentic-http-wip.json)，上一单元见 [恢复与单元回归快照](verification/agentic-recovery-wip.json)，初始基础检查点见 [agentic-main-wip.json](verification/agentic-main-wip.json)。上一完整阶段标签为 `jdk8-m14`；默认八个支持模块和兼容 BOM 不变。

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


## 第三个 WIP 单元：HTTP/SSE 与异步流式交接

- `AgenticHttpJdk8Test` 在真实 Java 8 上使用随机 loopback 端口与 synthetic-agentic 鉴权，不读取环境变量或外部模型配置。共 18 项网络场景，HTTP 请求和工具结果均经实际 OpenAI/OkHttp 兼容适配器。
- 首次网络测试复现 async 中间 Agent 将 TokenStream 直接交给下一步文本消费者而发生 ClassCastException。AgentExecutor 现在在调度时捕获中间/末端流决策；异步 worker 内启动并等待中间流的文本，再完成 AsyncResponse，避免嵌套 deferred 值。末端流继续交给调用方，start 前不发 HTTP。
- 确定性竞争测试令最后一步开始后才允许异步 Agent 创建 TokenStream，验证 planner 前进到 terminated 不改变先前派发 Agent 的中间流身份。原同步分支及公开签名保持不变。
- 覆盖同步 HTTP、异步 HTTP 与独立下一步重叠、同步/异步中间 SSE 文本、同步/异步末端惰性 TokenStream、UTF-8 跨字节分段、单次完成、显式 StreamingHandle 取消、HTTP 拒绝、同步/异步流超时/错误及 dispatcher/server handler 释放。取消不发送完成/错误回调，符合既有 StreamingHandle 合同；调用方 executor 保留所有权。
- 四种工具回路（同步/异步 HTTP，同步/异步中间 SSE）各验证实际工具执行一次、两次模型请求、分段工具参数、Unicode 参数和带 call ID 的结果回传；结构化 JSON 转为 POJO 后传给下一 Agent。
- 会话测试使用子 Agent 显式 @MemoryId 与 chatMemoryProvider，验证同 ID 历史、不同 ID 隔离以及单独清理 chat memory/scope 后重新开始。首次用例遗漏子 Agent 的 @MemoryId 而错误地期望根 scope ID 自动替换默认 AI Services memory ID，现按固定接口显式配置；没有改变生产记忆分配或 scope eviction 语义。
- OpenAI 与 OkHttp 新增在主 Agentic POM 的 test scope，不改变生产依赖树。剩余网络故障下的完整补偿、所有 error-recovery 策略、异步持久化、复杂 planner/supervisor/声明式/可观测性及其余三个 Agentic 模块继续待推进；当前测试不能代替完整支持或 BOM 独立消费。


第三单元最终 clean 验证通过：实际 Java 8 380 项（Agentic 34），选定 JDK 17 1115 项，TCK 62 通过/14 官方 untested，共 1557 通过；API、测试来源、生产依赖、字节码、运行时链接及既有 BOM 消费全部通过。新增 Agentic BOM 独立消费仍 pending，最新结果见 verification/agentic-http-wip.json。


## 第四个 WIP 单元：延迟错误恢复、重试与网络工具补偿

- 新增 14 项真实 Java 8 网络用例。先复现四个中间 SSE 恢复/重试失败和异步 HTTP 重试多登记一次调用的问题，再修复；不将最初失败的运行计为通过。
- 中间流的 HTTP/SSE 错误包装为 AgentInvocationException，进入同一 AgenticScope error handler。RETURN_RESULT 完成延迟状态，THROW_EXCEPTION 保留原因并传播，只有显式 RETRY 才重新执行尝试。重新解析输入，使处理器对普通输入键的更新进入下一请求。
- 已发布的延迟操作在重试时不再调用 completeAgentInvocation，不重复写入调用历史或向 planner 发送成功通知；保留一次逻辑 Agent 调用。错误回调在 HTTP reader 之外恢复，单线程 dispatcher 测试验证重试不会阻塞同一个 reader。
- 私有 RecoverableStream 统一中间流结果与恢复 Future，以原子终止标志保护回调，并将恢复处理器自身异常传入结果。重试循环处理多次失败后返回回退值，不增加嵌套的流恢复任务。末端 TokenStream 的显式回调和取消合同保持原样。
- 用例覆盖同步/异步 SSE 的回退与重试、同步 HTTP 回退、异步 HTTP 重试记录、重试修改输入、三次失败后的回退以及恢复处理器抛错；处理后的请求/dispatcher/server handler 均释放。
- 实际网络工具通过同步/异步 HTTP 和同步/异步中间 SSE 执行后，后续 Agent 失败时各补偿一次。两个异步 SSE 工具的执行日志为 do:first/do:second/undo:second/undo:first；错误被恢复为正常结果时，不补偿之前成功的工具。
- 不新增生产依赖或公开签名；新桥接类为 AgentExecutor 的私有嵌套类型。不是所有恢复/补偿能力的完成声明：输入与输出同键的重试、自引用 async scope function、挂起与异步恢复组合、持久化延迟结果、末端流恢复和全局中间流取消仍需专项验证；也未覆盖所有补偿失败与并行调度。


第四单元最终 clean exit 0：Java 8 394 项（Agentic 48），JDK 17 1115 项，TCK 62 通过/14 官方 untested，共 1571 通过。API/来源审计无回退，生产依赖树不变；附加 JAR 209 个 classfile 52 类无禁止引用，实际 Java 8 扩展链接 5155 类，仍保留原三个可选平台排除。全部既有 BOM 示例通过；新桥接为私有类型，完整 Agentic 与其独立消费继续 pending。


## 第五个 WIP 单元：异步输出绑定、同键重试与延迟状态回填

- 新增 `@Internal` 的 internal.DeferredScopeContext/Frame，以调用内绑定保存 output key 的派发输入。异步执行体和中间流恢复在该绑定内运行；同一输出键的 readState/writeState/writeStateIfAbsent/writeStates 访问调用内值，其他消费者仍等待共享 pending 结果。其他键维持原 scope 行为。
- 错误处理器可读取初始输入，也可更新同键输入供下一次模型请求使用，不会等待自身 Future 或把中间输入提前泄漏给下游。state() 仍为原始共享状态 Map，未改为自动解包视图；这里的绑定应用于 scope 的状态读写方法。
- Frame 按 scope 实例隔离，支持嵌套恢复，finally 恢复上层/移除 ThreadLocal。单线程池的异常嵌套与复用测试验证不残留上次调用的绑定。内部工具类型增加不代表原 API 签名变化；二进制对照计数指固定基线可见类型，不是候选 JAR 的全部类型数。
- readState 递归展开嵌套 DelayedResponse，但只在共享值仍为原 pending 对象时回填；rootCallEnded 的 drain 同样使用条件替换。旧等待者可取得其旧结果，而后来的赋值/新 pending 不被旧读取或 root drain 覆盖。
- scope 展开 AsyncResponse 后遇到未完成 SuspendedResponse 时发出 AgenticSystemSuspendedException，进入 planner 已有的受控挂起路径，避免直接 blockingGet 等待人工输入。直接调用 SuspendedResponse.blockingGet 的原始阻塞 API 不变。
- 测试用有界真实 Java 8 子进程隔离自等待回归；子进程超时会被终止并等待回收，不把永远等待的 worker 留在测试 JVM。覆盖自引用非 AI async scope function、私有中间写入、同键 HTTP/SSE 重试及更新、async HITL 后接同步消费者的挂起恢复、陈旧读/drain 和线程池上下文生命周期。
- 本单元不证明任意并行共享键的事务语义。末端 async HITL、并发异步挂起/错误恢复组合、原始 state Map 的自等待用法、延迟状态持久化和全局取消仍待专项验收；全部 Agentic 支持与独立 BOM 消费仍 pending。


第五单元最终 clean exit 0：实际 Java 8 404 项（Agentic 58）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1581 通过。原基线 API 与测试来源审计通过，生产依赖不变。候选 JAR 211 类全部 classfile 52，无禁止引用；Java 8 链接 5157 类，三个既有可选平台排除不变。既有 BOM 示例通过；新增内部 Frame 工具和全部支持边界见 verification/agentic-deferred-state-wip.json。


## 第六个 WIP 单元：末端等待与异步挂起传播

- 五个初始回归均失败，复现末端 HITL 恢复后重新询问、重复等待重复执行、末端 async 响应阻塞，以及 async scope function 的挂起被包装为普通错误；修复后再扩大到 8 项真实 Java 8 场景。
- planner 已完成但仍有待人工响应时，在该 planner 的执行状态内保存 __terminalAwaiting 和可选 __terminalResult。重复调用继续返回挂起；响应完成后从该终点取结果并清理检查点，不再调用 firstAction 或重发已完成的人工步骤。这两个内部字段是兼容分支的检查点扩展，不声明原始上游能直接恢复它们。
- 新增 @Internal DefaultAgenticScope.resolveAsyncStates()，用于根 planner 收尾时展开 AsyncResponse 外层计算而不阻塞等待其返回的人工响应标记；按既有身份条件回填保留并发更新。它是一个内部公开辅助方法，原 AgenticScope 接口与原有签名保留，API 审计显式列为新增项。
- 非 AI action/function 包装器和延迟错误恢复保留 AgenticSystemSuspendedException 的控制流身份；根执行器不将该异常送入失败补偿。输出求值成功之后才清理执行状态，避免输出中发现挂起时丢失恢复点。
- 未完成的 async 消费者从保存的派发点重新进入，失败恢复处理器不会被调用。测试明确消费者有两次进入、人工提供者只有一次；这是方法级恢复，不是从任意 Java 代码行继续执行。
- 八项场景包括同步/异步末端、重复等待、普通返回值的异常式挂起、async 人工提供者+async 消费者、工具执行后挂起不补偿且恢复不重放，以及两个独立 Java 8 进程的末端检查点写入/恢复。跨进程夹具为同步 HITL、单 writer 文件 store 和正常退出，不扩大为 async 持久化或断电恢复声明。
- 并行/嵌套 async 挂起、async supervisor、控制挂起尝试的历史结果归一化与延迟状态持久化、末端流恢复和全局取消仍待专项验收；其余三个 Agentic 模块与完整长期范围保持 pending。


第六单元最终 clean exit 0：Java 8 412 项（Agentic 66）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1589 通过。原基线 API 全部保留，单个新增内部 resolveAsyncStates 方法在审计中明确报告；生产依赖和 211 类 classfile 52 产物/5157 类链接、原始测试来源与既有 BOM 示例均通过。结果见 verification/agentic-terminal-suspension-wip.json，完整支持仍未完成。
