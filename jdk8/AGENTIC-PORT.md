# Agentic 主模块基础移植（WIP）

这是第十五阶段的可检查增量，尚未完成 Agentic 的全部验收。最新异步持久化检查点见 [检查点快照](verification/agentic-async-persistence-wip.json)，此前并行/规划检查点见 [异步规划快照](verification/agentic-async-planning-wip.json)，此前末端挂起检查点见 [挂起恢复快照](verification/agentic-terminal-suspension-wip.json)，此前状态绑定检查点见 [异步状态快照](verification/agentic-deferred-state-wip.json)，此前恢复/补偿检查点见 [错误恢复快照](verification/agentic-error-recovery-wip.json)，此前网络检查点见 [HTTP/SSE 快照](verification/agentic-http-wip.json)，上一单元见 [恢复与单元回归快照](verification/agentic-recovery-wip.json)，初始基础检查点见 [agentic-main-wip.json](verification/agentic-main-wip.json)。上一完整阶段标签为 `jdk8-m14`；默认八个支持模块和兼容 BOM 不变。

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


## 第七个 WIP 单元：并行/嵌套恢复与异步 supervisor

- 初始三个真实 Java 8 场景复现并行消费者恢复被跳过、嵌套流程误存为末端等待，以及 supervisor 未等异步人工输入就继续规划的问题。修复后扩大到六项，增加重复等待、两个并行输入的部分完成和异步 query 的下一轮规划。
- scope 使用每个 Agent 最新的原始调用结果识别以受控挂起结束的 async 尝试；恢复 planner 时从已完成集合移除这些子 Agent，只重入未完成方法。并行副作用与人工提供者均只执行一次；消费者在响应完成后第二次进入，不声明任意代码行恢复。
- 输出求值发现本 planner 的未完成 async 子 Agent 时保存常规执行状态，而不是已完成的终点检查点；嵌套流程从未完成的步骤恢复。async barrier 跳过当前 worker 自己拥有的 output key，避免等待自身。
- supervisor 只等待被选中的上一调用，不 drain 其他并行任务。人工响应未完成时不调用规划模型；检查点保存 awaitingOutputKey/lastResponse，恢复后将实际回答传给模型。异步 query 结果在下一规划请求之前就绪；executionState 保留不可修改 Map 合同。
- AgentInvocation 新增包内 rawOutput；DefaultAgenticScope 新增两个公开 @Internal 辅助方法 suspendedAsyncAgentIds/resolveAsyncState，API 审计显式报告与既有 resolveAsyncStates 合计三项新增。原 AgenticScope 接口不变。
- 六项用例在有界真实 Java 8 子进程内验证，超时后强制回收；显式 parallel executor 关闭并等待终止。多个人工输入均可见，完成一个仍挂起且不提前重入消费者，全部完成后两消费者各重入一次。
- 此单元不证明所有并发调度或跨进程 async 恢复；异常 Future 调用历史归一化、延迟持久化、全局取消、其余 Agentic 策略及其他三个模块继续 pending，BOM 尚不纳入 Agentic。


第七单元完整 clean exit 0：真实 Java 8 418 项（支持模块 346 + Agentic 72）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1595 通过。原 API 成员与测试来源保留，显式记录三个内部新增方法；生产依赖不变，211 类附加产物与 5157 类链接及既有独立 BOM 示例通过。快照见 verification/agentic-async-planning-wip.json；完整 Agentic 与长期目标继续 WIP。


## 第八个 WIP 单元：异步检查点值与跨进程恢复

- 新增真实 Java 8 跨 JVM 用例复现 AsyncResponse 留在调用历史、新 JVM 受类型 allowlist 拒绝的问题。未通过放宽 CompletableFuture/AsyncResponse 反序列化限制解决。
- serializableCopy 对顶层状态和调用输出展开已完成 AsyncResponse/已完成 DeferredResponse；未完成或以受控挂起结束的 async 尝试不写运行时包装器。调用历史以 null 输出保留原调用标识，私有 incompleteAsyncAgentIds 记录需要方法级重入的 Agent。实际活跃 scope/调用历史不被快照改写。
- 快照不等待计算，避免在 checkpoint 写锁内等待需要 scope 读锁的 worker。内部集合使用带公开无参构造器的 ConcurrentSkipListSet，避免 KeySetView 的反序列化失败；二次快照保留从磁盘恢复的未完成标记，登记新调用后清除该标记。
- 根流程返回挂起之前再次 checkpoint，保存 async 完成之后才出现的人工响应和最终 planner 恢复位置，避免只留下派发时的快照。
- 三项新增场景：async 人工响应跨两个实际 Java 8 JVM；async 受控挂起消费者跨两个 JVM；实际持久化写锁下对未完成计算取快照、读取并二次复制，再释放 worker 并验证完成值/历史归一化。执行日志仍严格 prepare/ask/finish，已完成步骤不重放。
- incompleteAsyncAgentIds 是兼容分支的私有 JSON 检查点扩展；它记录未完成方法，不保存 Java 栈。此单元不证明任意进程强杀时间点、任意 planner 的在途任务调度恢复、外部副作用恰好一次或多 writer 原子性。异步普通失败历史、嵌套容器内用户自放 Future、全局取消和其余 Agentic 功能继续待推进。


第八单元完整 clean exit 0：真实 Java 8 421 项（支持模块 346 + Agentic 75）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1598 通过。原基线 API 与三个已记录内部增项保持，测试来源及生产树不变；211 类附加产物、5157 类实际 Java 8 链接及既有 BOM 消费通过。最新快照 verification/agentic-async-persistence-wip.json；完整 Agentic 与长期目标仍未完成。


## Patterns 联动检查点

patterns 基础移植暴露 router 动态结果被通用终点检查点冻结的问题，现保留非空 planner 结果的原恢复逻辑；主 Agentic 75 项及其全部原始 251 项回归保持通过。详见 [PATTERNS-PORT.md](PATTERNS-PORT.md) 与 verification/agentic-patterns-foundation-wip.json。其他主模块缺口仍待推进。


## Patterns 异步消费与错误边界联动

AgenticJdk8 增加 invocationOutput 内部辅助方法，AgentExecutor 以私有异常标记隔离 planner 回调与 Agent 恢复，再在执行边界还原；非 AI 登记避免提前求失败 Future。主模块 76 项实际 Java 8 与全部 251 项原始 JDK 17 保持通过，新增私有类使候选主 JAR 为 212 类。详见 PATTERNS-PORT.md 与 verification/agentic-patterns-async-wip.json；完整主 Agentic 仍 WIP。


## Debate 恢复调度联动

AgenticJdk8 增加内部 PLANNER_MANAGES_RESUME 常量，Root 完成过滤仅对显式声明自己管理完整恢复调度的 planner 让权。普通并行/条件等既有过滤不变，原 Planner 接口和主模块 76 项 Java 8/251 项原始 JDK 17 回归保持。详见 PATTERNS-PORT.md 与 verification/agentic-debate-recovery-wip.json；完整主 Agentic 仍 WIP。

## 失败专用收尾检查点（WIP）

- 初始六项有界 Java 8 场景中，四项在失败后等待人工/外部响应而超时，listener 的 AssertionError 另替换了原始失败；async 人工 marker 的一层展开为对照。
- 新增 @Internal DefaultAgenticScope.rootCallFailed(registry, listener, primary)，只展开已完成的 DelayedResponse，未完成的响应/计算不等待。成功的 rootCallEnded 保持原有等待合同；此单元不把错误返回等同于所有后台工作已取消。
- 失败收尾仍尝试 ephemeral scope eviction 或持久化；registered/persistent scope 保留可观察状态，人工 marker 不被伪造完成。从收尾步骤逃逸的异常附加为 suppressed，保留原始异常对象、类型和原因。已发生的同一异常不重复附加，也不自我 suppressed。
- AgenticJdk8 新增公开内部 suppressCleanupFailure(Throwable,Runnable)，Root 的补偿、错误通知、失败收尾分别保护，某一步失败不阻止后一步。原补偿动作/普通 listener 异常的上游日志处理合同不变；新增方法不修改原 Planner 或 AgenticScope 接口。
- 八项真实 Java 8 子进程验证：SuspendedResponse、PendingResponse、async 人工响应、async 失败、保存错误/原始异常、listener Error/临时 scope eviction、错误返回先于未完成计算、失败后保存仍未回答的 marker。未完成计算夹具在接到原始错误后才显式释放并等待结果，说明当前没有假称取消已完成。
- 不承诺失败历史的完整持久化/失败后自动重试、所有补偿失败组合或后台网络工作取消。普通同步 store/listener 的自定义阻塞行为也没有被改造成异步超时接口；全局取消与任务/流生命周期继续待推进。


失败收尾检查点完整 clean exit 0：真实 Java 8 459 项（支持模块 346 + 主 Agentic 84 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1706 通过。原基线 API 保留，新增 rootCallFailed 内部方法明确列为第四个 DefaultAgenticScope 增项；来源/生产依赖审计保持，main 212 类/patterns 22 类均 classfile 52，扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-failure-cleanup-wip.json；完整工程仍 WIP，无新阶段标签或 Agentic BOM 支持声明。


### Root 失败后的 async 任务归属与中断（WIP，2026-10-21）

- 新增五项实际 Java 8 子进程验收先全部失败：错误返回后后台仍运行、输出键覆盖丢失追踪、忽略中断的任务继续写状态、跨 memoryId 隔离，以及等待外部 PendingResponse 的任务不能中断。
- 每个 root 调用维护内部任务登记，不依赖当前 state 的输出键；AsyncResponse 在提交前登记取消动作，失败 root 在补偿之前取消自己的登记任务并中断执行线程。完成后解除登记；线程引用在归还执行器前清除，避免误中断后续工作。
- DeferredScopeContext 捕获调用归属并在 finally 恢复 ThreadLocal；取消与 scope 写入共享边界。已取消任务调用 writeState/writeStateIfAbsent/writeStates、发布解析结果或登记调用时抛 CancellationException，外部调用者仍可检查/更新 scope。取消不进入 Agent 错误恢复。
- owned 任务读取外部 DeferredResponse 时使用既有可中断等待，不取消或伪造外部 marker 的回答；取消后的 AsyncResponse 检查点按未完成处理，不持久化运行时 Future。
- 原失败收尾 inflight 用例从夹具释放后完成改为要求 owned future 已取消；继续保留原始错误、仅一次恢复和人工 marker 未回答的断言。
- 内部 API 增项：DefaultAgenticScope.invocationTasks()/cancelPendingTasks(Throwable)、AsyncResponse.isCancelled()，以及新增 InvocationTasks/Registration/Binding 辅助类型；包名及基线签名保持。无新增生产依赖。
- 验证：完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0。真实 Java 8 464 项（支持模块 346 + 主 Agentic 89 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1711 通过。生产依赖树未变，main 216 类/patterns 22 类均 classfile 52，无禁止引用；main 扩展链接 5162 类、main+patterns 5184 类及既有独立 BOM 消费通过。基线 API/测试来源审计通过，主模块基线可见类型增项累计七个方法。快照 `verification/agentic-cancellation-wip.json`；无新完整阶段标签或 Agentic BOM 支持声明。
- 边界：Java 无法强制终止忽略中断的用户代码，直接修改 state() 原始 Map 或外部副作用不受 scope 方法守卫覆盖。网络流取消、同步并行派发失败收尾、成功调用被覆盖任务的完整收尾、失败历史与自动重试仍待完成；不改变全部 Agentic/MCP/模型/向量库与最终交付的长期范围。


### 同步并行派发失败收尾（WIP，2026-10-21）

- 三项实际 Java 8 子进程先全部失败：默认/自定义执行器等待六秒慢任务并丢失先发生的失败，第二次派发拒绝后首个任务不被中断。
- 同步并行派发也注册到当前 root 的 InvocationTasks。全部正常完成仍等待全部结果；任一任务失败即唤醒 root，根调用按既有失败边界取消其他 owned 任务，保留首次观察到的错误。拒绝提交仍抛原始拒绝异常，已提交任务由 root 收尾取消。
- 继续使用用户提供的 Executor，不关闭调用者执行器；取消后的同步 Agent 异常不再进入恢复处理。直接 inline 执行的 Executor 仍按调用者的同步调度合同执行，不能声称在 executor.execute 返回之前抢占它。
- 验证：完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0。真实 Java 8 467 项（支持模块 346 + 主 Agentic 92 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1714 通过。API/测试来源审计保持，main 216 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5184 类与全部既有独立 BOM 消费通过，无生产 POM/依赖变化。快照 `verification/agentic-parallel-failure-wip.json`；仍 WIP，无新阶段标签。网络流取消、成功调用被覆盖任务收尾、完整失败历史/自动重试以及所有其余长期范围保持 pending。


### 中间流与 OkHttp 调用取消（WIP，2026-10-21）

- 改造前五个真实 Java 8 socket 子进程均显示 root 失败后连接未关闭，覆盖同步/异步中间流在响应头前/首段之后，以及异步普通对话在响应头前。
- core 新增内部 CancellationContext，将资源登记者显式绑定到调用及 OkHttp callback，finally 恢复原上下文；不向无关 executor 任务隐式传播。OkHttp 同步/异步/SSE 请求在执行或 enqueue 之前登记 Call.cancel，终止后解除登记；callback 内的默认工具续传仍沿用原 owner，不关闭共享客户端/执行器。
- StreamingResponse 和框架 RecoverableStream 登记 future 取消，忽略取消后的终止/恢复结果；OwnedResponse 区分框架任务与外部回答，取消值在失败收尾中不替换原错误，检查点不持久化 future。
- 七个网络场景以 server socket EOF、dispatcher running/queued=0、结果已终止且抛 CancellationException、仅一次错误恢复为证据。额外验证直接 HTTP executeAsync 以及 SSE 工具结果触发第二请求后的取消，默认工具只执行一次。夹具清理 cancelAll 在断言之后，不将夹具关闭当作成功证据。
- 首轮全量回归暴露双人工 marker 偶发缺失，新增确定性实际 Java 8 用例在旧逻辑失败：先结算到受控挂起就退出。现先结算其余 async provider，再返回受控挂起；普通失败立即抛出，不直接等待外部 marker 的回答。
- 三项支持模块 Java 8 用例验证无 owner 不取消资源、嵌套/异常恢复和 executor 回调的显式捕获/线程复用无泄漏。生产不新增外部依赖；StreamingResponse.isCancelled() 为内部 API 增项，AsyncResponse/StreamingResponse 保留基线直接 DelayedResponse 接口声明，由内部 OwnedResponse 辅助判断取消状态；不放宽接口审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：实际 Java 8 478 项（支持模块 349 + 主 Agentic 100 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1725 通过。33 JAR/6372 基础类条目审计，默认链接 4949 类；main 217 类/patterns 22 类均 classfile 52，扩展链接 5188 类及全部既有 BOM 消费通过。严格基线 API 和测试来源审计通过，主模块增项累计八个内部方法，无新增生产依赖。快照 `verification/agentic-network-cancellation-wip.json`；仍 WIP，无新完整阶段标签或 Agentic BOM 支持声明。
- 边界：本单元覆盖默认 OkHttp 与框架消费的中间流；terminal TokenStream、任意自定义模型/传输、HTTP 回调的 scope 写入归属和异步工具执行器的取消/上下文传播仍需验收。资源 registrar 不等于恢复全部 Agentic ThreadLocal。成功调用被覆盖任务收尾、完整失败历史/自动重试、scope guard 与用户同步回调的阻塞边界，以及全部其余 Agentic/MCP/模型/向量库范围继续 pending。


### HTTP 回调的 scope 代际归属（WIP，2026-10-22）

- 三个定向实际 Java 8 用例先复现失败：捕获回调取消后仍可写入、嵌套回调未恢复 owner、旧回调无法隔离新的 root 代际。SSE 工具夹具修正为先半关闭服务端输出触发协议 EOF 后，明确复现三种迟到写入全部未被拒绝；夹具早期等待超时不计为有效证据。
- CancellationContext.Registrar 新增内部默认 inContext(Supplier) 钩子，保留单抽象方法合同；InvocationTasks 用私有 registrar bridge 把捕获的代际绑定到 scope 守卫。嵌套及异常时分别恢复之前的 scope/resource 上下文，无隐式线程继承。
- 回调绑定本身不提前拒绝取消：HTTP onFailure/onResponse 仍可关闭响应、解除登记并传递失败。调用者真正写 scope 或开始新工作时，原有 check/mutate 根据捕获代际拒绝；不会错误采用 scope 新调用的 owner。
- 四项定向验收覆盖三种写入、不同 scope 嵌套、线程复用无残留、同一 scope 新旧代际，以及 callback Error 保留和双绑定恢复。新增真实 SSE 工具回调用例在 root 返回原始失败后显式释放被门控的工具，断言拒绝三种迟到写入并保留外部写入的新值；原七项网络资源验证继续保留。半关闭输出用于触发工具执行，不把该场景的 EOF 单独当作 root 取消导致关闭的证据。
- 本单元不改变基线公共类型/方法；新增默认钩子位于本分支新增的内部辅助接口，私有 bridge 不扩展 InvocationTasks 的公开方法。无生产依赖变化。最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 483 项（支持模块 349 + 主 Agentic 105 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1730 通过。严格基线 API/测试来源审计保持，main 217 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5188 类及全部既有 BOM 消费通过，生产依赖树未变。快照 `verification/agentic-callback-scope-wip.json`；无新完整阶段标签或 Agentic BOM 支持声明。
- terminal TokenStream、任意自定义传输、异步工具执行器、用户自己创建线程的上下文传播仍待完成；直接 state() Map 写入、外部副作用不受此守卫合同覆盖。成功收尾/完整失败历史/自动重试、同步用户回调与 guard 锁边界及全部其他长期范围继续 pending。


### AI Services executor 上下文组合（WIP，2026-10-22）

- 现有 CapturedContextSupport 只捕获 ExecutorProvider 上下文。四项基础实际 Java 8 测试中三项先失败（owner 丢失），无上下文的原 executor/null 合同用例通过；自定义工具 executor 的迟到写入也复现为三次写入全部未拒绝。工具续传请求原先仍在 HTTP 回调上发起，已有 owner，新增续传用例作为回归而非失败证据。
- captureInto 将当前 CancellationContext owner 与原 SPI CapturedContext 组合，保留 provider 的捕获/包装时机和恢复合同；toBuilder 继续沿用不可变捕获快照。既有工具及完成响应 executor 的 restoringIn 因此携带同一 root 归属，不替换或关闭调用者 executor，不改 Guardrails 实现。
- 四项基础用例验证无 provider 时的 owner、InvocationContext 复制、与用户 ThreadLocal 快照组合、worker 原值恢复、callback Error 原对象与上下文恢复，以及无上下文的 executor/null 合同。
- 两项新增 SSE 子进程使用自定义单线程工具 executor：失败后释放门控工具，拒绝三种 scope 写入且保留外部新值；工具在线程中创建的新 HTTP future 立即取消；正常工具续传保持既有归属。验证工具确实运行在指定线程、executor 未被框架关闭，并在同一线程复用时无 scope/resource 残留。原八项网络场景保留。
- 完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 489 项（支持模块 353 + 主 Agentic 107 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1736 通过。严格基线 API/来源审计保持，main 217 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5188 类及全部既有 BOM 消费通过。快照 `verification/agentic-executor-context-wip.json`。没有公开签名或生产依赖变化，Agentic 仍不纳入默认 BOM，无新阶段标签。
- 本单元传播上下文，不宣称工具线程已被强制终止或所有排队任务已取消。完整 executor 任务所有权/中断/排队取消、terminal TokenStream、自定义模型回调/传输、用户自行创建线程、成功收尾与失败历史/自动重试、guard 锁边界和全部其他长期范围继续 pending。



### root 调用边界失败收尾（WIP，2026-10-22）

- 新增 AgenticRootBoundaryJdk8Test，八项初始场景中七项在改造前失败：beforeCall RuntimeException/Error、前/后 listener Error、最终持久化 RuntimeException/Error 和 ephemeral beforeCall 失败均未取消已登记资源。listener 普通 RuntimeException 的日志并继续合同原本通过。
- executeAgentMethod 在 scope 取得之后，将 root owner 初始化、参数写入、beforeCall、前后 listener、planner、输出结算、挂起检查点与最终 root 收尾纳入统一失败保护。Exception 或 Error 先取消该 root，再尽力补偿、通知错误与清理/持久化失败状态；保留原异常身份，清理异常 suppressed，不引入重试或副作用重放。
- rootCallStarted 移到 beforeCall 之前；同会话再次调用时，beforeCall 获取新 owner，不再拿到上一成功调用的 finished owner。新增代际测试证明第二调用失败取消其资源，上一成功 owner 仍可保留其延迟资源。
- 正常挂起仍是控制流，不取消资源或发错误通知；挂起通知 Error 则按失败路径取消并保留 unanswered marker。共十一项真实 Java 8 场景，断言均在 fixture cleanup 前执行；包含最终 save 与失败 snapshot 的两次尝试、onError 清理 Error 的 suppressed 身份、临时 scope 驱逐及持久会话保留。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：Java 8 验收 586 项（支持模块 422 + 主 Agentic 135 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1833 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接和全部既有 BOM 独立消费通过；主 Agentic 严格 API、原始测试来源、生产依赖图及产物审计通过，无新增公开成员、生产类型或依赖。快照 [agentic-root-boundary-wip.json](verification/agentic-root-boundary-wip.json)。仍 WIP、无新完整阶段标签，Agentic 不进入默认 BOM。
- 本组使用登记资源探针，不把它宣称为所有第三方 I/O 强制终止证明；既有实际 HTTP/SSE 回归继续运行。scope 获取/创建时的 store 或 afterAgenticScopeCreated 失败、任意不配合组件、terminal 工具组合及完整生命周期仍需继续验证。Guardrails、默认 BOM 和生产依赖保持既定范围，Agentic 四模块与其他长期范围尚未完成。

### terminal TokenStream 延迟启动的 root 归属（WIP，2026-10-22）

- 新增十二项真实 Java 8 子进程，使用实际 Agentic 终端工作流和 loopback socket/SSE。改造前十二项均失败：响应头前/首段后 root 取消不关闭延迟启动的 Call，成功/HTTP 错误回调丢失捕获代际，启动前取消仍调用模型。另一次双用例定向检查直接证明已取消 root 的模型调用次数为 1，而预期为 0。
- AiServiceTokenStream.start 通过已有 runWithOwner 恢复 InvocationContext 中捕获的 owner，再执行原启动过程；派发前登记取消检查，已取消 root 以 CancellationException 交付错误，不调用模型接口。正常工作流已结束的 finished owner 仍允许合法延迟启动；模型/传输登记在创建时 owner，而不是启动线程的其他 root 上。
- 同步/异步 writer 各覆盖响应头前、首段后、启动前取消、同会话新旧代际、正常 Unicode 完成与 HTTP 400；另加两项 SSE 错误帧场景。新旧代际测试从 fresh root 绑定内部启动 old stream，确认调用线程绑定恢复、fresh 取消不影响 old stream、old 取消确实关闭连接。成功/错误/部分响应 callback 都校验真实 InvocationTasks scope 绑定。
- 运行中取消用服务端读取到 socket EOF、dispatcher running/queued=0、一次错误且无成功终止为证据；这些断言早于 fixture cancelAll/关闭。启动前取消同时检查模型调用次数与 HTTP 请求数均为 0。正常成功/错误场景不把正常 EOF 当取消证据。既有上下文 handle 主动取消且不发 terminal 的回归保持。
- 初次 clean 虽通过，但把终止计数断言加强到 dispatcher 完全排空后，四项真实场景再失败：首段/代际取消先报错误，随后又交付成功。原因是 SSE parser 已报告读取错误后返回，传输仍发 onClose，OpenAI StreamingRequestExecutor 未区分错误终止与正常完成。现每次请求使用私有原子终止状态，错误/完成只 claim 一次，迟到事件忽略；错误时取消可用 parsing handle，取消失败 suppressed 到原始错误，不遮蔽错误交付。传输层 onClose 的资源生命周期合同保持。
- 两个额外 Agentic SSE 错误帧场景不调用 root.cancel，由客户端主动释放解析连接，验证服务端 EOF、单一错误和无成功终止；共十四项子进程。另六项 Java 8 SPI 回调测试覆盖 error→close、完成后迟到事件、空流重复关闭、partial consumer 失败、parsing handle 取消失败和 error 处理器异常；无可用取消句柄的旧式客户端保留原错误与 suppressed 诊断。终止计数全部在 dispatcher 排空后再次检查。
- 新增的 error-event 错误处理器失败检查先复现异常被静默吞掉；错误事件交付现移出 JSON/partial 解析捕获块，保持一次通知并将处理器异常交给原调用通道。六项 SPI 场景均在实际 Java 8 运行。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：Java 8 验收 575 项（支持模块 422 + 主 Agentic 124 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1822 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接及全部既有 BOM 独立消费通过；严格 API、原始测试来源、生产依赖树和产物审计通过。TokenStream 对照为 1 可见类型/16 声明成员/24 继承公开条目，无公开成员增删；本单元无新增生产类型或依赖。快照 [agentic-terminal-stream-wip.json](verification/agentic-terminal-stream-wip.json)。仍 WIP，不新增完整阶段标签、不将 Agentic 纳入默认 BOM。
- 本组 terminal 网络夹具不含工具；提前工具执行、工具续传与 handle 主动取消的组合仍需专项验证。不新增公开成员、生产类型或依赖；不改变 Guardrails 实现。此处保留 terminal 输出的调用者消费语义，不引入返回之后的自动工作流重放或工具请求重试。任意不配合的自定义传输/SDK 仍需各模块验证；完整成功/失败收尾、其他 Agentic 四模块能力、剩余 MCP/模型/向量库/BOM 与最终交付继续 pending。

### 完成响应 executor 的任务归属（WIP，2026-10-22）

- 用显式捕获的 InvocationContext 构造低层 AiServiceTokenStream，并以真实 loopback OpenAI SSE 测试 completeResponseProcessingExecutor。八个场景先有五个失败：取消后排队任务仍交付成功、运行任务未中断、拒绝提交与 provider.wrap 失败时错误回调 owner 丢失、provider runnable 失败时流未终止。后者还触发 @After 的未捕获异常检查，Surefire 初次报告多一条清理失败记录；不当作额外独立用例。
- 完成响应派发改用 CapturedContextSupport.supplyAsync，沿用已有 root 任务登记、排队跳过、运行中断及 provider 上下文恢复。TokenStream 保存原始调用者 executor，避免队列外层再持有捕获上下文；调用者 executor 不被关闭。future 失败统一向 onError 交付，取消后 worker 再退出不重复交付错误。
- 新增 public @Internal CapturedContextSupport.runWithOwner(InvocationContext,Runnable)，从捕获上下文恢复终端回调的 cancellation owner；provider 包装只在任务提交时执行，终端失败交付可在 provider 失败或任务未开始时进入原 owner。无已捕获 owner 时保留调用线程原行为。错误处理器自身的 RuntimeException/Error 若逃出原 onError 合同，显式转交当前线程 uncaught 通道，避免被 CompletableFuture 回调静默捕获。
- 八项实际 Java 8 SSE 用例覆盖排队取消/不打断无关 blocker、运行中断/不写入消息、provider 只包装一次/工作线程恢复、拒绝提交原异常、provider 包装与 runnable 两类失败、处理 Error 原身份、错误处理器 Error 通道。所有关闭/解除登记断言在测试清理前执行；本组网络正常完成后再取消处理任务，不将正常 EOF 当作取消网络的证据。
- 新增一项 Agentic Java 8 回调代际用例：旧捕获 owner 已取消且新 root 活动时，runWithOwner 拒绝三种迟到写入、保留新状态，抛 Error 后恢复新 root 的 resource/scope 绑定。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：Java 8 验收 555 项（支持模块 416 + 主 Agentic 110 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1802 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接及全部既有 BOM 独立消费通过；严格 API/原始测试来源/生产依赖树审计通过。新 TokenStream 局部对照为 1 可见类型/16 声明成员/24 继承公开条目，无公开成员增删；core helper 明确报告新增 runWithOwner，连同此前 supplyAsync 共 2 项内部公开方法增项。无新增生产类型或依赖。快照 [agentic-completion-executor-wip.json](verification/agentic-completion-executor-wip.json)。仍 WIP，无新完整阶段标签，不将 Agentic 纳入默认 BOM。
- 本单元使用低层参数中的显式 executor，不新增高层 AI Services 配置入口。任意阻塞回调/拒绝中断的 provider 不获得强制停止或新超时合同。terminal TokenStream、完整成功/失败收尾、全部 Agentic 四模块/剩余 MCP/模型/向量库/BOM 与最终交付继续 pending，Guardrails 实现不改动。

### 同轮多工具取消与清理异常传播（WIP，2026-10-22）

- 扩展 loopback HTTP/SSE 夹具，每次第一响应包含 call1/Shanghai 与 call2/Beijing 两个工具，第二响应正常结束。十项实际 Java 8 多工具测试中九项先通过；一项复现清理错误在高层 Future 包装之间丢失，而另一个工具仍成功取消。不把该失败解释成取消循环未处理其他工具。
- 四项通用取消传播测试中三项先失败：直接清理异常、嵌套清理异常和多路径去重；普通完成/null root 合同原本通过。CompletableFutureUtils.propagateCancellation 现将直接 cancel 抛出的 RuntimeException/Error，以及已取消下游 Future 收集的 suppressed 错误，传到上游取消异常；按对象身份去重并避免 self-suppression。只在已取消/已完成的下游读取该信息，不在尚未完成的拒绝取消 provider 上挂等待回调。
- 十项 HTTP/SSE 用例覆盖 Future/Publisher 同轮两个 pending provider、一个运行/一个排队、一个 provider 拒绝取消但继续清理另一工具，以及成功路径的结果 ID 对齐、补偿模式逆请求顺序回滚一次。补偿测试故意反序完成两个原始 Future；默认取消不继续请求模型，调用者池保持打开，排队工具不执行。
- 拒绝取消的 provider 仍需要自行结束；响应式取消失败按既有日志路径记录，不向已取消订阅补发 terminal。取消传播诊断不是外部副作用回滚或强制终止用户 I/O 的保证。Guardrails 代码保持未改动。
- 新增受影响 core CompletableFutureUtils 的固定基线 API 审计；其余已有 API/来源/产物/链接和消费验证继续保留。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：Java 8 验收 546 项（支持模块 408 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1793 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接及全部既有 BOM 独立消费通过；严格 API/原始测试来源/有效依赖树审计通过。新增 core helper 对照为 1 可见类型/1 声明成员/10 继承公开条目/0 record，无公开成员缺失或新增；本轮没有新增生产类型或依赖。快照 [agentic-tool-fanout-cancellation-wip.json](verification/agentic-tool-fanout-cancellation-wip.json)。Agentic 仍 WIP、未进入默认 BOM。
- 完成响应 executor、terminal 流、完整成功/失败收尾、所有 Agentic 四模块能力、剩余 MCP/模型/向量库/BOM 与最终交付继续 pending；本单元不创建完整阶段标签。

### 高层 Future/Publisher 的工具轮次取消（WIP，2026-10-22）

- 修正 loopback SSE 夹具，使连续 tool 模式也能发送工具事件，之后十二项实际 Java 8 端到端测试先复现十项失败：Future/Publisher 未取消程序化或注解 Future、未跳过排队工具、未中断运行工具、指定调用的取消未到达其工具。两个启用补偿的 drain/rollback 用例原本通过，不把夹具早期缺失的工具事件当有效失败证据。
- AsyncToolLoop 在工具 supplier 提交前登记调用者取消，后创建的工具也立即观察已取消信号；memory 添加之后再次检查取消，避免继续启动新轮次。单次 ToolTask 继续负责运行 worker、排队入口与迟到 Future，调用者池保持打开。取消清理失败附加到调用者取消异常，不把它转成新的模型请求。
- 响应式 Loop 为每个订阅维护并发活动工具集合，取消时遍历快照，工具完成解除登记，发布窗口之后再次检查取消。缺失 CompleteToolCall 时启动的回退工具同样登记。取消清理异常写日志并继续取消其他工具，不在已取消订阅上发 onError/onComplete。
- DefaultToolExecutor 新增私有 AsyncToolResult，将取消穿过注解异步值转换传到原始 CompletableFuture，保留 mayInterruptIfRunning 和清理异常。另两个定向用例先失败：注解工具原始 Future 独立取消被错误转文本并成功返回。现保留 CancellationException，绕过工具错误文本策略；高层 Future 作为失败交给调用者（不冒充主动 cancel），Publisher 正常收到 onError，不发下一轮请求。
- 显式启用 compensateOnToolErrors 时保留固定基线 drain-then-rollback：已启动工具不因高层取消丢失迟到成功结果，待其完成再收集并补偿一次。它不同于默认模式的 best-effort 取消，不承诺补偿模式能强制停止未知外部副作用。相应两条 HTTP/SSE 回归继续验证回滚一次、无下一模型请求。
- 新增十六项实际 Java 8 用例覆盖上述调用模式、隔离、补偿、独立取消、注解 Future 转换的取消模式升级/清理失败。既有 25 项任务/单工具/归属回归全部保留。新增受影响 DefaultToolExecutor 的固定基线 API 对照脚本，不将局部审计当整个主模块 API 审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 532 项（支持模块 394 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1779 通过。33 JAR/6384 基础类条目、4955 类默认链接、5194 类扩展链接及全部既有 BOM 独立消费通过。ToolService 5 类型/89 声明/117 继承公开条目/4 record，DefaultToolExecutor 2 类型/17 声明/30 继承公开条目/0 record，严格基线对照无公开成员缺失或新增；Agentic/patterns/受影响 core helper API 与原始测试来源审计通过，生产依赖树未变。快照 [agentic-tool-loop-cancellation-wip.json](verification/agentic-tool-loop-cancellation-wip.json)。Agentic 仍 WIP、未纳入默认 BOM，无新完整阶段标签。
- 本组 HTTP/SSE 取消夹具每次模型响应只含一个工具；同一响应中多工具 fan-out 取消仍需专门验收。任意拒绝取消的 provider/CompletionStage 适配器、同步阻塞回调或 inline executor 不因此获得强制停止与新超时保证；Guardrails 不纳入本单元改造。完成响应 executor、terminal 流、完整成功/失败收尾、所有 Agentic 四模块能力、剩余 MCP/模型/向量库/BOM 与最终交付继续 pending。

### 单次工具调用的组合 Future 取消（WIP，2026-10-22）

- 六项实际 Java 8 定向测试先失败五项：同步 CancellationException 被错误策略转为成功文本、直接取消未到达 provider Future、运行 supplier 未中断、排队 supplier 仍执行、cancel(false) 后迟到的 provider Future 丢失。成功完成后取消返回 false 的用例原本通过。
- startTool 用私有 ToolTask 管理单次调用的 supplier worker、启动 Future 与 provider 原始 Future。直接取消先标记本任务并传播，再终止对外 Future；队列出队不执行 supplier，运行 supplier 在 cancel(true) 时中断，迟到 Future 按已记录模式取消，cancel(false) 可以升级为 true。线程引用在归还调用者 executor 前清除；不关闭调用者 executor，不中断队列中其他任务。
- Future 完成/取消回调均在登记锁外执行；provider cancel 抛异常时继续处理其他已登记 Future，最终仍终止对外 Future，并向取消调用者保留清理错误及 suppressed 错误。root 归属先于迟到 Future 的直接取消处理登记，清理失败不送进工具业务错误策略。provider 同步抛出 CancellationException 现与返回 Future 的取消一样绕过错误转文本策略。
- 新增八项实际 Java 8 验收覆盖上述五个缺口、成功后取消、false→true 升级与 provider cancel 抛错。既有 8 项委托工具（含 loopback HTTP 正常续传/取消后无下一请求）和 9 项基础任务取消回归保留。
- 新增 `audit-tool-service-api.sh`，从固定 Git 导出 ToolService 并编译 Java 17 对照。首次严格对照发现四个 record 替代类的 12 个自动值方法漏 final；补齐修饰符，保留比较器原有严格规则。该检查只覆盖受影响 ToolService 与其可见嵌套类型，不代表整个主模块 API 审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 516 项（支持模块 378 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1763 通过。33 JAR/6382 基础类条目、4954 类默认链接、5193 类扩展链接与全部既有 BOM 独立消费通过；严格 ToolService/Agentic/patterns/受影响 core helper API、原始测试来源及生产依赖树审计通过。ToolService 无公开成员增项，12 个值方法修饰符恢复基线；新增私有 ToolTask 及 javac 合成访问标记。快照 [agentic-tool-cancellation-wip.json](verification/agentic-tool-cancellation-wip.json)。仍 WIP、无新完整阶段标签，不将 Agentic 纳入默认 BOM。
- 单次 startTool 的直接取消不代表高层 AI Service Future/Publisher 或整个工具轮次的反向传播已完成；这些路径继续验收。自定义 Future/I/O 可拒绝取消，忽略中断的用户代码不能强制停止；同步用户回调阻塞没有新增超时保证。完成响应 executor 任务生命周期、terminal 流、完整成功/失败收尾及全部 Agentic/MCP/模型/向量库与交付范围仍 pending。

### 委托异步工具 Future 的归属（WIP，2026-10-22）

- 四项定向实际 Java 8 测试先复现三处失败：supplier 已返回后委托 Future 未取消，成功 after 通知和错误处理均丢失 owner；无 owner 的隔离用例原本通过。
- ToolService 在拿到 ToolExecutor.executeAsync 返回 Future 后登记 root 取消，保留独立等待结果；成功/失败完成时进入捕获的 CancellationContext，恢复原绑定并解除登记。取消在 provider 返回前发生时，迟到 Future 登记后立即取消。
- 通过返回 Future 报告的 CancellationException 不再转交工具错误策略，不生成可送回模型的工具成功结果；避免自定义“错误转文本”策略把取消变为下一轮模型请求。已有 AsyncNotSupportedException 的显式失败行为保留。
- 八项实际 Java 8 验收覆盖 root 取消、异线程成功通知/原异常、无 owner 隔离、provider 返回时取消、提前完成/解除登记，以及 loopback OpenAI HTTP 的正常工具结果回传与取消后无后续请求。该组使用可取消 CompletableFuture，不声称可强制停止拒绝取消的第三方 I/O。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 508 项（支持模块 370 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1755 通过。33 JAR/6378 基础类条目、4952 类默认链接、5191 类扩展链接、全部既有 BOM 独立消费，以及严格 API/测试来源审计通过；生产依赖树无变化，无新增公开声明或类。快照 [agentic-delegated-tool-wip.json](verification/agentic-delegated-tool-wip.json)。Agentic 继续 WIP、未纳入默认 BOM，无新完整阶段标签。
- 当前只证明 root 归属取消；直接取消 startTool 返回的组合 Future 到委托 Future 的反向传播，以及 provider 同步抛出 CancellationException 时的策略，仍待实现/验收。完成响应 executor、terminal 流、自定义传输、全部 Agentic/MCP/模型/向量库及交付范围继续 pending。

### 工具 supplier 的中断与排队取消（WIP，2026-10-22）

- 两项真实 Java 8 SSE 子进程先失败：运行工具没有中断，排队工具 future 未终止导致 HTTP 完成回调继续等待。现工具 supplier 提交到 executor 之前按 InvocationContext owner 登记，取消立即终止 future；运行任务中断实际 worker，排队任务出队时跳过 supplier。调用者 executor 与其中不属于本调用的阻塞任务保持运行。
- 新增内部 CapturedContextSupport.supplyAsync，使用私有 OwnedTask/OwnedCapturedContext/RestoringExecutor。future 完成回调不在 worker 锁中执行；worker 引用在归还 executor 前清除；cancel(false) 后运行 supplier 仍保持 owner 登记，root 后续可中断。排队取消清除本任务持有的 supplier、上下文命令和登记，不承诺从任意用户 executor 的物理队列移除 entry。
- 同一捕获上下文的 restoring executor 可识别并避免重复包装；无 owner 时仍走标准 CompletableFuture 路径。TokenStream 工具调度与 ToolService 并行/启动 supplier 接入统一入口，保留调用者执行器；Guardrails 实现不修改。
- 九项实际 Java 8 基础测试覆盖运行中断、排队 future 立即终止且出队不执行、cancel(false)、拒绝提交原异常/解除登记、已取消 owner 跳过提交、SPI 单次包装/恢复和 supplier 原始错误；另外复现并修复上下文清理 Error 被吞，以及调用者提前完成排队 future 时登记未解除。已发布结果保留，后续 provider 清理 Error 继续交给 executor 的 uncaught-error 通道。
- 新增的两项 SSE 场景验证工具业务方法确实被中断，或仍被调用者工作占住的 executor 中排队工具被取消；HTTP 回调在释放夹具 blocker 前已退出，之后出队也不执行工具。既有迟到写入夹具改为故意忽略中断，以继续验证拒绝三种迟到写入，未删除原断言。
- 固定基线受影响 core helper 的严格 API 对照新增 audit-captured-context-api.sh；既有签名保持，明确报告新增 public @Internal supplyAsync(Supplier,Executor,InvocationContext)。该审计只覆盖受影响 helper，不冒充整个 core 的完整 API 对照。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 500 项（支持模块 362 + 主 Agentic 109 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1747 通过。33 JAR/6378 基础类条目审计、4952 类默认链接与全部既有 BOM 独立消费通过；main 217 类/patterns 22 类均为 classfile 52、无禁止引用，扩展链接 5191 类。严格 Agentic/patterns API、受影响 core helper API 及原始测试来源审计通过，生产依赖树未变。快照 [agentic-owned-task-wip.json](verification/agentic-owned-task-wip.json)。Agentic 仍不纳入默认 BOM，无新完整阶段标签。
- 任意忽略中断的用户代码不能强制停止，物理队列属于调用者；自定义 ToolExecutor.executeAsync 返回的委托 stage 取消、完成响应 executor 的完整任务生命周期、terminal TokenStream、自定义传输、成功收尾与完整失败历史/自动重试、guard 锁边界及全部长期范围继续 pending。
