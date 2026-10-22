# Java 8 移植进度

## 当前状态：第十五阶段 Agentic 高层工具轮次取消检查点（WIP，2026-10-22）

- 上一完整阶段标签仍为 `jdk8-m14`。Agentic 主模块已开始改造，尚未通过完整功能验收或纳入 BOM；整体目标未完成。
- 四模块 187 文件的固定基线声明快照仍见 [AGENTIC-AUDIT.md](AGENTIC-AUDIT.md) 与 [AGENTIC-API-INVENTORY.json](AGENTIC-API-INVENTORY.json)。当前移植与覆盖边界见 [AGENTIC-PORT.md](AGENTIC-PORT.md)。
- `agentic-wip` 显式 profile 编译主模块全部 158 个基线生产文件及 4 个新增兼容辅助文件，保留默认八模块支持范围。生产和专项测试均 release 8；15 项基础行为、2 项恢复场景、32 项网络场景、18 项状态/重入/挂起场景、7 项异步规划场景、3 项异步持久化场景、8 项失败收尾、5 项取消、3 项同步并行失败、12 项网络取消及 4 项回调归属场景在真实 Java 8 执行。
- 主模块 25 个 record 已转为 POJO，补齐 JSON 构造元数据、访问器与自动值方法 final 修饰符；保留对象相等性。高版本 API、switch/模式匹配/text block/var 已降级，并修正模式绑定的重复读取。私有接口辅助方法移入包内类，避免扩大接口 API。
- 新增固定 Git 源码 → Java 17 对照产物 → Java 8 候选的二进制 API 审计，覆盖可见类型、声明/继承成员和 bridge；不将声明数量当成功能验收。验证命令与快照见 AGENTIC-PORT.md。
- 主模块有效生产依赖树仅包含兼容 main/core 与既有 Jackson、SLF4J、JSpecify、Reactive Streams、OpenNLP，无官方 SNAPSHOT 或新增生产库。patterns 生产树亦已审计；MCP/A2A 两模块的有效依赖/功能仍待完成。
- 下一单元：主 Agentic 的剩余 IT/端到端回归、async/streaming、HITL/挂起恢复、记忆与补偿、planner/supervisor/声明式和可观测性；继续 patterns 的完整能力及 MCP/A2A 全部能力、BOM 和独立消费。MCP 旧双端点及其他剩余能力、全部模型/向量库继续保留。













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

### AI Services executor 上下文组合（WIP，2026-10-22）

- 现有 CapturedContextSupport 只捕获 ExecutorProvider 上下文。四项基础实际 Java 8 测试中三项先失败（owner 丢失），无上下文的原 executor/null 合同用例通过；自定义工具 executor 的迟到写入也复现为三次写入全部未拒绝。工具续传请求原先仍在 HTTP 回调上发起，已有 owner，新增续传用例作为回归而非失败证据。
- captureInto 将当前 CancellationContext owner 与原 SPI CapturedContext 组合，保留 provider 的捕获/包装时机和恢复合同；toBuilder 继续沿用不可变捕获快照。既有工具及完成响应 executor 的 restoringIn 因此携带同一 root 归属，不替换或关闭调用者 executor，不改 Guardrails 实现。
- 四项基础用例验证无 provider 时的 owner、InvocationContext 复制、与用户 ThreadLocal 快照组合、worker 原值恢复、callback Error 原对象与上下文恢复，以及无上下文的 executor/null 合同。
- 两项新增 SSE 子进程使用自定义单线程工具 executor：失败后释放门控工具，拒绝三种 scope 写入且保留外部新值；工具在线程中创建的新 HTTP future 立即取消；正常工具续传保持既有归属。验证工具确实运行在指定线程、executor 未被框架关闭，并在同一线程复用时无 scope/resource 残留。原八项网络场景保留。
- 完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 489 项（支持模块 353 + 主 Agentic 107 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1736 通过。严格基线 API/来源审计保持，main 217 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5188 类及全部既有 BOM 消费通过。快照 `verification/agentic-executor-context-wip.json`。没有公开签名或生产依赖变化，Agentic 仍不纳入默认 BOM，无新阶段标签。
- 本单元传播上下文，不宣称工具线程已被强制终止或所有排队任务已取消。完整 executor 任务所有权/中断/排队取消、terminal TokenStream、自定义模型回调/传输、用户自行创建线程、成功收尾与失败历史/自动重试、guard 锁边界和全部其他长期范围继续 pending。

### HTTP 回调的 scope 代际归属（WIP，2026-10-22）

- 三个定向实际 Java 8 用例先复现失败：捕获回调取消后仍可写入、嵌套回调未恢复 owner、旧回调无法隔离新的 root 代际。SSE 工具夹具修正为先半关闭服务端输出触发协议 EOF 后，明确复现三种迟到写入全部未被拒绝；夹具早期等待超时不计为有效证据。
- CancellationContext.Registrar 新增内部默认 inContext(Supplier) 钩子，保留单抽象方法合同；InvocationTasks 用私有 registrar bridge 把捕获的代际绑定到 scope 守卫。嵌套及异常时分别恢复之前的 scope/resource 上下文，无隐式线程继承。
- 回调绑定本身不提前拒绝取消：HTTP onFailure/onResponse 仍可关闭响应、解除登记并传递失败。调用者真正写 scope 或开始新工作时，原有 check/mutate 根据捕获代际拒绝；不会错误采用 scope 新调用的 owner。
- 四项定向验收覆盖三种写入、不同 scope 嵌套、线程复用无残留、同一 scope 新旧代际，以及 callback Error 保留和双绑定恢复。新增真实 SSE 工具回调用例在 root 返回原始失败后显式释放被门控的工具，断言拒绝三种迟到写入并保留外部写入的新值；原七项网络资源验证继续保留。半关闭输出用于触发工具执行，不把该场景的 EOF 单独当作 root 取消导致关闭的证据。
- 本单元不改变基线公共类型/方法；新增默认钩子位于本分支新增的内部辅助接口，私有 bridge 不扩展 InvocationTasks 的公开方法。无生产依赖变化。最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：真实 Java 8 483 项（支持模块 349 + 主 Agentic 105 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1730 通过。严格基线 API/测试来源审计保持，main 217 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5188 类及全部既有 BOM 消费通过，生产依赖树未变。快照 `verification/agentic-callback-scope-wip.json`；无新完整阶段标签或 Agentic BOM 支持声明。
- terminal TokenStream、任意自定义传输、异步工具执行器、用户自己创建线程的上下文传播仍待完成；直接 state() Map 写入、外部副作用不受此守卫合同覆盖。成功收尾/完整失败历史/自动重试、同步用户回调与 guard 锁边界及全部其他长期范围继续 pending。

### 中间流与 OkHttp 调用取消（WIP，2026-10-21）

- 改造前五个真实 Java 8 socket 子进程均显示 root 失败后连接未关闭，覆盖同步/异步中间流在响应头前/首段之后，以及异步普通对话在响应头前。
- core 新增内部 CancellationContext，将资源登记者显式绑定到调用及 OkHttp callback，finally 恢复原上下文；不向无关 executor 任务隐式传播。OkHttp 同步/异步/SSE 请求在执行或 enqueue 之前登记 Call.cancel，终止后解除登记；callback 内的默认工具续传仍沿用原 owner，不关闭共享客户端/执行器。
- StreamingResponse 和框架 RecoverableStream 登记 future 取消，忽略取消后的终止/恢复结果；OwnedResponse 区分框架任务与外部回答，取消值在失败收尾中不替换原错误，检查点不持久化 future。
- 七个网络场景以 server socket EOF、dispatcher running/queued=0、结果已终止且抛 CancellationException、仅一次错误恢复为证据。额外验证直接 HTTP executeAsync 以及 SSE 工具结果触发第二请求后的取消，默认工具只执行一次。夹具清理 cancelAll 在断言之后，不将夹具关闭当作成功证据。
- 首轮全量回归暴露双人工 marker 偶发缺失，新增确定性实际 Java 8 用例在旧逻辑失败：先结算到受控挂起就退出。现先结算其余 async provider，再返回受控挂起；普通失败立即抛出，不直接等待外部 marker 的回答。
- 三项支持模块 Java 8 用例验证无 owner 不取消资源、嵌套/异常恢复和 executor 回调的显式捕获/线程复用无泄漏。生产不新增外部依赖；StreamingResponse.isCancelled() 为内部 API 增项，AsyncResponse/StreamingResponse 保留基线直接 DelayedResponse 接口声明，由内部 OwnedResponse 辅助判断取消状态；不放宽接口审计。
- 最终完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0：实际 Java 8 478 项（支持模块 349 + 主 Agentic 100 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1725 通过。33 JAR/6372 基础类条目审计，默认链接 4949 类；main 217 类/patterns 22 类均 classfile 52，扩展链接 5188 类及全部既有 BOM 消费通过。严格基线 API 和测试来源审计通过，主模块增项累计八个内部方法，无新增生产依赖。快照 `verification/agentic-network-cancellation-wip.json`；仍 WIP，无新完整阶段标签或 Agentic BOM 支持声明。
- 边界：本单元覆盖默认 OkHttp 与框架消费的中间流；terminal TokenStream、任意自定义模型/传输、HTTP 回调的 scope 写入归属和异步工具执行器的取消/上下文传播仍需验收。资源 registrar 不等于恢复全部 Agentic ThreadLocal。成功调用被覆盖任务收尾、完整失败历史/自动重试、scope guard 与用户同步回调的阻塞边界，以及全部其余 Agentic/MCP/模型/向量库范围继续 pending。

### 同步并行派发失败收尾（WIP，2026-10-21）

- 三项实际 Java 8 子进程先全部失败：默认/自定义执行器等待六秒慢任务并丢失先发生的失败，第二次派发拒绝后首个任务不被中断。
- 同步并行派发也注册到当前 root 的 InvocationTasks。全部正常完成仍等待全部结果；任一任务失败即唤醒 root，根调用按既有失败边界取消其他 owned 任务，保留首次观察到的错误。拒绝提交仍抛原始拒绝异常，已提交任务由 root 收尾取消。
- 继续使用用户提供的 Executor，不关闭调用者执行器；取消后的同步 Agent 异常不再进入恢复处理。直接 inline 执行的 Executor 仍按调用者的同步调度合同执行，不能声称在 executor.execute 返回之前抢占它。
- 验证：完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0。真实 Java 8 467 项（支持模块 346 + 主 Agentic 92 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1714 通过。API/测试来源审计保持，main 216 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5184 类与全部既有独立 BOM 消费通过，无生产 POM/依赖变化。快照 `verification/agentic-parallel-failure-wip.json`；仍 WIP，无新阶段标签。网络流取消、成功调用被覆盖任务收尾、完整失败历史/自动重试以及所有其余长期范围保持 pending。

### Root 失败后的 async 任务归属与中断（WIP，2026-10-21）

- 新增五项实际 Java 8 子进程验收先全部失败：错误返回后后台仍运行、输出键覆盖丢失追踪、忽略中断的任务继续写状态、跨 memoryId 隔离，以及等待外部 PendingResponse 的任务不能中断。
- 每个 root 调用维护内部任务登记，不依赖当前 state 的输出键；AsyncResponse 在提交前登记取消动作，失败 root 在补偿之前取消自己的登记任务并中断执行线程。完成后解除登记；线程引用在归还执行器前清除，避免误中断后续工作。
- DeferredScopeContext 捕获调用归属并在 finally 恢复 ThreadLocal；取消与 scope 写入共享边界。已取消任务调用 writeState/writeStateIfAbsent/writeStates、发布解析结果或登记调用时抛 CancellationException，外部调用者仍可检查/更新 scope。取消不进入 Agent 错误恢复。
- owned 任务读取外部 DeferredResponse 时使用既有可中断等待，不取消或伪造外部 marker 的回答；取消后的 AsyncResponse 检查点按未完成处理，不持久化运行时 Future。
- 原失败收尾 inflight 用例从夹具释放后完成改为要求 owned future 已取消；继续保留原始错误、仅一次恢复和人工 marker 未回答的断言。
- 内部 API 增项：DefaultAgenticScope.invocationTasks()/cancelPendingTasks(Throwable)、AsyncResponse.isCancelled()，以及新增 InvocationTasks/Registration/Binding 辅助类型；包名及基线签名保持。无新增生产依赖。
- 验证：完整 `./jdk8/build.sh -o -Pagentic-wip` exit 0。真实 Java 8 464 项（支持模块 346 + 主 Agentic 89 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1711 通过。生产依赖树未变，main 216 类/patterns 22 类均 classfile 52，无禁止引用；main 扩展链接 5162 类、main+patterns 5184 类及既有独立 BOM 消费通过。基线 API/测试来源审计通过，主模块基线可见类型增项累计七个方法。快照 `verification/agentic-cancellation-wip.json`；无新完整阶段标签或 Agentic BOM 支持声明。
- 边界：Java 无法强制终止忽略中断的用户代码，直接修改 state() 原始 Map 或外部副作用不受 scope 方法守卫覆盖。网络流取消、同步并行派发失败收尾、成功调用被覆盖任务的完整收尾、失败历史与自动重试仍待完成；不改变全部 Agentic/MCP/模型/向量库与最终交付的长期范围。

### 失败专用收尾与原始异常（WIP）

- 初始六项场景中四项等待人工/外部响应超时，listener Error 另替换原始失败。现在 Root 普通失败走 rootCallFailed，仅结算已完成值，不等未回答的 marker 或未完成计算；成功收尾的等待合同不变。
- 补偿、错误通知、失败收尾分别保护，逃逸的次级异常以 suppressed 附加，保留原始异常对象/类型/原因；临时 scope 仍尝试移除，持久化仍尝试保存观察状态，不伪造人工回答。
- 八项实际 Java 8 子进程含 Pending/Suspended/async 人工响应、async 失败、保存错误、listener Error/临时移除、错误先于 gated 后台计算结束及失败后保存未回答 marker。后台任务在夹具接到错误后由夹具显式释放，不把返回错误冒充取消完成。
- 失败收尾检查点完整 clean exit 0：真实 Java 8 459 项（支持模块 346 + 主 Agentic 84 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1706 通过。原基线 API 保留，新增 rootCallFailed 内部方法明确列为第四个 DefaultAgenticScope 增项；来源/生产依赖审计保持，main 212 类/patterns 22 类均 classfile 52，扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-failure-cleanup-wip.json；完整工程仍 WIP，无新阶段标签或 Agentic BOM 支持声明。
- 后台任务/网络流取消、完整失败历史与自动重试、补偿/观测及全部长期未完成范围继续 pending。

### Debate 轮次、人工辩论者与 judge（WIP）

- 最初七个实际 Java 8 场景均失败，包含恢复结果 null 和清空上一轮上下文。现在恢复轮次、消息、本轮完成 ID、judge 阶段/完成标志/结果；只有本轮位置全部可用后才推进收敛判断和下一阶段。
- 人工 judge 返回 marker 之后不会重复询问；人工辩论者完成响应后使用当前 scope 值，保留此前完成的位置。两个消费者在第二轮参数绑定时挂起，恢复后都执行，第一轮不重放。
- 新内部 __plannerManagesResume 标记声明 planner 自身恢复完整阶段/派发集合，避免不区分轮次的通用完成 ID 误删下一轮同一 Agent 调用；普通 planner 默认过滤不变，Planner 接口不新增方法。
- 七项场景含同步/异步辩论者、同步/异步 judge、第二轮和两个 writer/reader JVM 组合，记录执行次数与上下文内容。Debate 恢复检查点完整 clean exit 0：真实 Java 8 451 项（支持模块 346 + 主 Agentic 76 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1698 通过。原基线 API/来源和生产依赖保持；新增内部恢复标记常量单列，main 212 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5180 类与既有 BOM 消费通过。快照 verification/agentic-debate-recovery-wip.json；完整工程仍 WIP，不新增阶段标签或 Agentic BOM 支持声明。
- 失败时未完成外部响应的清理、全局取消/完整补偿/观测，以及其余所有长期范围继续 pending。

### Voting 人工输入与检查点（WIP）

- 最初五个恢复场景均被有界 Java 8 子进程超时回收。现持久化已完成/跳过 ID 和票值，仅派发未完成步骤；外部响应标记通过当前 scope 值解析，全部就绪后才交给 strategy。
- 七项新增实际 Java 8 场景含同步/异步人工票、重复等待、部分回答、受控 async 消费者，以及三个 writer/reader JVM 组合。固定票与人工提供者各一次，消费者仅在回答后方法重入；skipped 条件跨 JVM 不重新检查。
- Voting 覆盖既有 executionState/restoreExecutionState 默认方法，内部 completed/votes 字段为本分支扩展。API 审计规范继承 owner/default 关键字差异，保留签名/泛型/throws 检查；独立反向夹具证明参数及泛型返回变化仍被拒绝，新增覆盖声明明确报告。
- Voting 恢复检查点完整 clean exit 0：真实 Java 8 444 项（支持模块 346 + 主 Agentic 76 + patterns 22）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1691 通过。原基线 API 保留，两个 Voting 检查点覆盖声明显式单列；比较器兼容/反向验证和来源/依赖审计通过；main 212 类/patterns 22 类均 classfile 52，扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-voting-recovery-wip.json；完整工程仍 WIP，不新增阶段标签或 Agentic BOM 支持声明。
- Debate 轮次与人工状态、失败时未完成外部响应清理、完整生命周期与所有长期未完成范围继续 pending。

### Patterns 异步/流式与错误边界（WIP）

- 初始两项实际 Java 8 子进程复现 Voting 把 <pending> 当数字，以及 Debate 将占位文本传给 judge；兼容辅助方法 AgenticJdk8.invocationOutput 取得选定计算/中间流的实际值，Voting/Debate/Decision Router 据此使用结果。
- 新增六项 patterns 实际 Java 8 场景，含并发投票、位置与 judge、默认三轮、router、中间流文本和原始失败原因/一次恢复。恢复最初观测 2 次；私有 planner 异常标记穿过当前 Agent 恢复 catch 后在 AgentExecutor 边界还原，不暴露新异常类型。
- 非 AI 登记先检查 ChatMessagesAccess 再求输出，避免提前读取已失败的 Future；主模块另增直接验证。原基线 API 对照保持，新增兼容类上的公开内部辅助方法与私有标记类型均单列。
- Patterns 异步检查点完整 clean exit 0：真实 Java 8 437 项（支持模块 346 + 主 Agentic 76 + patterns 15）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1684 通过。原基线 API/来源和生产依赖不变；新增兼容辅助方法单列，main 212 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-patterns-async-wip.json，仍为 WIP，无新阶段标签或 BOM 支持声明。
- Voting/Debate 人工挂起、轮次 checkpoint/跨进程恢复、其他 patterns 的异步与生命周期及全部长期范围继续 pending；没有将外部响应标记强行作为普通值处理。

### Patterns 基础移植与 router 恢复（WIP）

- 全部 15 个 patterns 生产文件进入 agentic-wip；四个 record（一个公开）和 Java 9+ 语法/API 转为 Java 8。仅依赖兼容主 Agentic，无新增生产库；详见 [PATTERNS-PORT.md](PATTERNS-PORT.md)。
- 九项真实 Java 8 场景覆盖七个 patterns 的基础流程、Desire 值/工厂合同及 router 人工响应恢复。全部八个原始 Test 类/70 项在独立 JDK 17 harness 执行，九个原始测试/辅助文件逐字校验，不加载外部模型 IT。
- 首轮两个原始 router 恢复断言失败，复现通用终点检查点冻结 <pending>；对有非空 planner 结果且无外部 output function 的暂停保留 planner 自身的恢复点，重算动态结果。原 null-result 末端不重放合同及主模块 75 项保持通过。
- 固定 Git 源码对照 API：16 可见类型/109 声明成员/235 继承公开项/一个公开 record 替代，无缺失/改变/增项；提取器文本块对应 annotation 常量逐字节一致。
- Patterns 基础检查点完整 clean exit 0：真实 Java 8 430 项（支持模块 346 + 主 Agentic 75 + patterns 9）、JDK 17 1185 项（含 patterns 原始 70 项）、TCK 62 通过/14 官方 untested，共 1677 通过。main/patterns API 与原始测试来源审计通过；patterns 22 类均 classfile 52，无禁止引用，扩展链接 5179 类及既有 BOM 消费通过。快照 verification/agentic-patterns-foundation-wip.json；仍属 WIP，无新阶段标签或 BOM 支持声明。
- patterns 全部策略/IT、模型路由与提取、并发 async/streaming/HITL/生命周期、主 Agentic 剩余能力、Agentic MCP/A2A 及长期范围继续 pending。

### 异步检查点与跨进程恢复（WIP）

- 新增异步人工响应跨 JVM 回归最初因调用历史中的 AsyncResponse 触发类型限制而失败。现将顶层状态与调用输出归一化为已完成值/可持久化人工标记，不放宽 Future 反序列化权限。
- 未完成计算或受控挂起的历史输出保存为 null，以私有 incompleteAsyncAgentIds 保存方法重入标识；再次快照保留标识，登记新调用后清理。快照不等待 worker，实际 scope 不变。
- 根流程暂停前保存 settled checkpoint；两个跨 JVM 场景保持 prepare/ask/finish 各一次，运行中 checkpoint 写锁用例验证不等 Future 并可读取/二次复制。使用单 writer 文件夹具和正常退出，不代表任意时刻强杀或外部副作用恰好一次。
- 第八单元完整 clean exit 0：真实 Java 8 421 项（支持模块 346 + Agentic 75）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1598 通过。原基线 API 与三个已记录内部增项保持，测试来源及生产树不变；211 类附加产物、5157 类实际 Java 8 链接及既有 BOM 消费通过。最新快照 verification/agentic-async-persistence-wip.json；完整 Agentic 与长期目标仍未完成。
- 普通 async 失败历史、任意在途调度恢复、全局取消、其余 Agentic/MCP/模型/向量库等继续 pending；Agentic 不进入默认 BOM，无新阶段标签。

### 并行/嵌套与异步 supervisor 检查点（WIP）

- 最新快照：[agentic-async-planning-wip.json](verification/agentic-async-planning-wip.json)。初始三个失败场景修复后扩展为六项，覆盖重复等待与多个并行人工输入的部分完成。
- 仅重入以受控挂起结束的 async 消费者；并行副作用和人工提供者不重放，嵌套未完成步骤不误存终点等待。supervisor 等待选定调用、保存待回答键并使用实际回答规划；异步 query 在下一轮规划前就绪。
- 新增两个 @Internal scope 方法，连同上一单元 resolveAsyncStates 合计三个内部 API 增项；原接口保留。supervisor 检查点扩展 lastResponse/awaitingOutputKey，结果 Map 仍不可修改。
- 第七单元完整 clean exit 0：真实 Java 8 418 项（支持模块 346 + Agentic 72）、JDK 17 1115 项、TCK 62 通过/14 官方 untested，共 1595 通过。原 API 成员与测试来源保留，显式记录三个内部新增方法；生产依赖不变，211 类附加产物与 5157 类链接及既有独立 BOM 示例通过。快照见 verification/agentic-async-planning-wip.json；完整 Agentic 与长期目标继续 WIP。
- 挂起尝试历史归一化/延迟持久化、全局取消及其余 Agentic/MCP/模型/向量库工作继续 pending；无新阶段标签，Agentic 不进入支持 BOM。

### 末端检查点与异步挂起传播（WIP）

`./jdk8/build.sh -o -Pagentic-wip` 最终完整 clean exit 0；最新快照：[agentic-terminal-suspension-wip.json](verification/agentic-terminal-suspension-wip.json)。

- 保存 planner 已完成、仅等待人工响应的终点检查点，完成响应后直接求值结果，不重新执行最后的提供者；重复等待保持挂起。内部字段 __terminalAwaiting/可选 __terminalResult 为兼容分支扩展。
- 非 AI wrappers、延迟恢复与根执行器保留受控挂起，不误触发失败恢复/补偿；输出求值之后才清理执行状态。根 planner 展开 async 计算结果以发现人工响应标记。
- 新增八项真实 Java 8 场景，含同步/异步末端、重复等待、异常式返回、async 消费者、已有 HTTP 工具挂起不补偿且恢复不重放、两个 JVM 的末端检查点写入/恢复。
- 实际 Java 8 共 412 项（支持模块 346 + Agentic 66），JDK 17 1115 项，TCK 62 通过/14 官方 untested；共 1589 通过，其余测试无失败/错误/跳过。
- 原 185 类型 API 保留，审计显式报告新增 @Internal DefaultAgenticScope.resolveAsyncStates()；来源/依赖/211 类附加产物/5157 类 Java 8 链接及既有 BOM 示例均通过。
- 并行/嵌套 async、async supervisor、挂起尝试的历史归一化/延迟持久化及全局取消等继续 pending，其他模块和长期范围不变。未新增完整阶段标签或 Agentic BOM 支持。

### 异步同键状态、重入与回填检查点（WIP）

`./jdk8/build.sh -o -Pagentic-wip` 最终完整 clean exit 0；最新快照：[agentic-deferred-state-wip.json](verification/agentic-deferred-state-wip.json)。

- 调用内 output key 绑定修复异步 scope function 与同键重试等待自身 Future，以及中间写入提前交付的问题。恢复处理器可更新同键输入供下一请求使用，绑定按 scope 实例隔离并在 finally 恢复/清理。
- readState 与 root drain 按 pending 对象身份条件回填，旧等待者不覆盖更新的状态。递归展开 async 返回的延迟响应，scope 遇到未完成 SuspendedResponse 进入受控挂起；验证 async HITL 后接同步消费者完成后仅执行一次人工请求。
- 新增 10 项真实 Java 8 场景，自等待回归放入有界子进程并回收；覆盖同键 HTTP/SSE 重试/更新、自引用读写、root drain 竞争、嵌套异常与线程池复用。
- 实际 Java 8 共 404 项（支持模块 346 + Agentic 58），JDK 17 1115 项，TCK 62 通过/14 官方 untested；共 1581 通过，其余测试无失败/错误/跳过。
- 原 185 类型 API 与测试来源审计通过；新增 @Internal DeferredScopeContext/Frame，不修改原公开签名或生产依赖。附加 JAR 211 类均 classfile 52，无禁止引用；实际 Java 8 扩展链接 5157 类，原三个可选平台排除不变，既有 BOM 示例通过。
- 原始 state() Map 不改为事务视图；末端/并发 async HITL、延迟持久化、全局取消及其余模块和长期范围继续 pending。无新阶段标签，Agentic 尚未加入默认 BOM。

### 延迟错误恢复、重试与补偿检查点（WIP）

`./jdk8/build.sh -o -Pagentic-wip` 最终完整 clean exit 0；最新快照：[agentic-error-recovery-wip.json](verification/agentic-error-recovery-wip.json)。

- 修复中间 SSE 错误绕过 scope 恢复策略、异步 HTTP 重试重复登记的问题。中间流错误转换为 AgentInvocationException；重试在已发布的延迟操作内执行，普通输入更新会带到下一请求，逻辑调用历史不重复。
- 私有 RecoverableStream 在 HTTP reader 外执行恢复/重试，原子保护终止并传递恢复处理器自身异常；保留末端 TokenStream 和同步普通调用合同。无新增生产依赖或公开签名。
- 新增 14 项实际 Java 8 网络用例：回退/重试与输入更新、单 HTTP reader、重复失败后的回退、处理器抛错、四路径工具补偿、逆序补偿和恢复成功不补偿。
- 真实 Java 8 共 394 项（支持模块 346 + Agentic 48），JDK 17 1115 项，TCK 62 通过/14 官方 untested；共 1571 通过，其余测试无失败/错误/跳过。
- 185 可见类型 API 对照、51 单元选择/58 原始源文件与夹具来源、209 类附加 JAR 的 classfile/API 引用和 5155 类实际 Java 8 链接及既有 BOM 示例均通过。
- 同键重试、自引用异步 scope function、延迟持久化、async/HITL、末端流恢复及全局中间流取消等仍需专项验收；其余三个 Agentic 模块和整个长期范围保留。当前无新阶段标签或 Agentic BOM 支持声明。

### HTTP/SSE 与异步流交接检查点（WIP）

`./jdk8/build.sh -o -Pagentic-wip` 完整 clean exit 0；最新快照：[agentic-http-wip.json](verification/agentic-http-wip.json)。

- 修复网络用例复现的 async 中间 TokenStream 被交给后续文本 Agent 而 ClassCastException：在调度时固定流决策，worker 内消费中间 SSE，末端仍惰性透传。确定性 planner 前进竞争、错误/超时和取消后的资源释放均有真实 Java 8 网络验证。
- 新增 18 项 loopback HTTP/SSE；四路径工具循环各实际执行一次并回传结果，另含结构化 POJO、显式 @MemoryId 会话隔离及独立记忆/scope 清理。测试 POM 新增 OpenAI/OkHttp test 依赖，生产树不变。
- 实际 Java 8 共 380 项（支持模块 346 + Agentic 34），JDK 17 1115 项（既有 864 + Agentic 251），TCK 62 通过/14 官方 untested；1557 通过，所有其余测试无失败/错误/跳过。
- 185 类型 API 对照、51 原始单元选择/58 源文件和夹具来源审计、208 类追加产物 classfile/API 引用、5154 类实际 Java 8 链接及所有既有 BOM 示例均通过。
- 未完成的全局中间流取消、完整错误恢复/补偿策略、异步持久化及其余三模块等继续保留。默认 BOM 尚无 Agentic，不新增阶段标签。

### 恢复与原始单元回归检查点（WIP）

`./jdk8/build.sh -o -Pagentic-wip` 新一轮完整 clean exit 0；最新快照：[agentic-recovery-wip.json](verification/agentic-recovery-wip.json)。

- 支持模块真实 Java 8 346 项，主 Agentic 16 项；新增两个独立 Java 8 进程的文件检查点恢复，执行日志严格为 prepare/ask/finish，恢复不重放前两步。
- 默认 JDK 17 选定上游 864 项，加上全部 51 个固定基线 Agentic 单元测试类 251 项。原断言保持不变；fixture audit 校验 51 个选择项及 58 个原始测试/辅助源文件内容。
- TCK 62 通过、14 官方 untested；本轮共 1539 通过、14 官方 untested，所有其余测试无失败/错误/跳过。API 对照、208 类附加 JAR 审计、5154 类实际 Java 8 链接及全部既有 BOM 示例均通过。
- `agentic-upstream-tests` 为 JDK 17 独立测试 harness；复制的共享流式夹具仅调整两个 Flow 导入，Invoice 嵌套夹具经固定基线 AST 校验，未加载外部模型 IT 配置。测试来源及边界见 harness README。
- Agentic 的完整 IT/端到端、其余模块与 BOM 消费仍待完成，当前不新增阶段标签。

### 基础移植检查点 `303f41467` 验证

`./jdk8/build.sh -o -Pagentic-wip` clean 全流程 exit 0；快照：[agentic-main-wip.json](verification/agentic-main-wip.json)。

- 原八模块真实 Java 8 346 项、主 Agentic 新增 14 项，均无失败/错误/跳过。
- JDK 17 选定上游仍为 864 项；Publisher TCK 62 通过、14 官方 untested。总计 1286 通过，14 官方 untested 单列。
- 原八模块 33 JAR / 6366 类审计与全部 BOM 示例通过；新增 Agentic JAR 208 类均 classfile 52，无禁止类引用。加入该 JAR 后真实 Java 8 链接 5154 类，仍只排除原 3 个可选平台入口。
- 固定基线主 Agentic API 对照通过：185 可见类型、1317 声明成员、2817 继承/公开项、22 可见 record 替代；无遗漏/改变成员或新增接口成员。Record 父类/反射元数据差异显式记录。
- 这是 WIP 证据，不替代完整 Agentic 功能/BOM 消费验收，因此不新增阶段标签。

## 第十四阶段现代 stdio/WebSocket 已完成（2026-10-21）

- 仓库 `/Volumes/Nas/codex-work/langchain4j-jdk8`，分支 `jdk8/main`；当前完整验收 `jdk8-m14`，上期 `jdk8-m13`；固定基线 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。
- 八个生产模块和兼容坐标不变，无新增生产依赖；整个 MCP、Agentic、模型/向量库及最终交付仍未全部完成。
- 固定基线全部 120 个 POM 的候选清单继续维护，不将声明审计计为功能实现或排除较难模块。

## 已完成能力

1. 首期五模块：core、HTTP 抽象、OkHttp、reactive-streaming、OpenAI 的 Java 8 源码与 Chat Completions 闭环。
2. 第二阶段加入 `langchain4j` 主模块：AI Services 接口代理、继承/非 public 接口的默认方法、可变参数/空数组/null、原始异常、注解提示词和可替换元数据 SPI。
3. 结构化 POJO 输出、Future、TokenStream 和 Reactive Streams Publisher；同步/异步会话隔离，以及 ChatMemoryAccess 查询与清理。
4. 自动工具循环：同步、Future、回调 SSE、响应式 SSE 四条路径，真实 loopback HTTP 联动，参数/结果回传、并发工具、失败和轮次限制。
5. Future/Publisher 取消后 HTTP 调用释放；内部取消专用回调与正常完成/失败区分。Java 17 下仍可反射读取 record 元数据。
6. 基础 RAG 全链路：加载、多文档分段/index/元数据、内存向量检索及过滤、JSON/文件持久化恢复、AI Services 上下文注入与来源；同步、Future、回调/响应式流式和 loopback HTTP/SSE。
7. 修复 RAG embedding 投影及 AI Services memory/augmentation 的取消链；验证检索失败、显式 offload 和保留问题原文的记忆配置。
8. OpenAI Embeddings HTTP：批量和顺序、model/token usage、参数/维度/Base64/listener、headers/query、错误/重试/超时与取消；HTTP embedding 接入四条 RAG 路径。
9. 修复 embedding 响应 index/数量/向量校验与底层 HTTP 独立取消后一直等待的问题；独立 BOM 的 HTTP RAG 示例在 Java 8 通过。
10. 独立 Java 8 官方 Publisher TCK（同线程/异步），取消/终止后 subscriber 引用释放；并发信号、终止竞争、GC、真实 SSE 溢出和取消释放。
11. 原始 AI Services 事件流在等待 RAG 时立即处理非法需求量；onSubscribe 立即取消不再启动检索，prologue/流式终止去重并释放 subscription 引用。
12. 新增 SQLite 文件向量存储：事务批量 upsert、类型保真元数据、精确余弦检索/过滤/删除、维度约束、重开恢复、锁/并发/连接生命周期；显式 offload 的四路径 RAG。
13. 独立 BOM 通过两个真实 Java 8 进程验证 HTTP embedding 入库退出，再重开 SQLite 完成异步 RAG，无重新入库。
14. SQLite 会话记忆：类型隔离 ID、核心消息/工具结果 JSON、整段事务更新/删除、同步和显式 Executor offload，与向量/RAG/四路径 AI Services 共存。
15. 修复窗口记忆和 AI Services 记忆组装的取消链；独立 JVM 恢复含工具调用/结果的历史，writer 4 条、reader 发 5 条并保存 6 条。

16. MCP 2025-11-25 工具子集：OkHttp HTTP JSON/连续 POST SSE、初始化/会话、工具分页/schema/调用、四条 AI Services 工具循环，以及直接取消/超时/close/并发和独立 BOM 消费。完整范围见 `../langchain4j-mcp/README-jdk8.md`。

17. MCP 会话 404 共享重新初始化、能力/缓存刷新、有界 DELETE、可选 GET SSE 与逐流 Last-Event-ID 恢复；并发/取消/超时/资源所有权和重试次数验证，原始工具 POST 不自动重放。

## 最终验证

`./jdk8/build.sh -o` 完整 clean、独立 BOM 消费及产物审计 exit 0。快照：[milestone-14.json](verification/milestone-14.json)。

| 验证 | 第十四阶段结果 |
|---|---|
| 八模块生产源码 | JDK 17 `--release 8` 通过 |
| 真实 Java 8 | 346 项，0 失败/错误/跳过；1.8.0_462 |
| JDK 17 选定上游 | 864 项，0 失败/错误/跳过；17.0.13 |
| 官方 Publisher TCK / Java 8 | 62 通过、14 官方 untested；无失败/错误或 required/optional 跳过 |
| 字节码/API 审计 | 33 JAR / 6366 基础类 |
| Java 8 类及成员链接 | 4946 类，保留 3 个可选平台排除 |
| 所有旧 BOM 示例 | AI Services、RAG、HTTP embedding、SQLite 跨进程、MCP 各此前传输/协议均通过 |
| 新增现代 stdio/WebSocket BOM | 每种发现 1、状态续传 1、实际执行 1、取消 1、订阅及进程/通道释放 |

新增 39 项 Java 8（34 个双通道参数化用例、5 个现代 WebSocket 恢复用例）；共 1272 项通过，另列 14 官方 untested。单方法筛选参数化测试曾选中 0 项，不计任何验证；最终完整套件有明确非零类/用例证据。

## 已知边界

- Java 8 的 Flow 签名替换、record/sealed 降级和线程模型差异继续适用；具体约定见 `COMPATIBILITY.md`。
- OpenNLP 2.x 不能运行于 Java 8。使用 1.9.5 和官方 0.35.0 历史中的 1.9.3 句子模型，未修改模型版本绕过校验；Java 8 和 6 个原始分割用例通过。1.9.x 已结束维护，模型结果可能与新版不同，来源和生命周期限制已记录。
- 基础 RAG 已通过本地模型和真实 OpenAI HTTP embedding 适配器两种流程验证（服务均为 loopback fixture）；真实远程模型/语义质量、其他 embedding 适配器/外部向量库、easy-rag 自动下载、复杂重排/多模态 RAG 尚未验收。Guardrails、Decision Services、复杂补偿事务、其余第三方适配器与 OpenAI Responses/音视频/图像仍保留未覆盖声明。
- SQLite 为精确全表扫描、Java 端过滤及阻塞 JDBC，异步 RAG 必须显式 offload；取消不能保证打断已开始的数据库扫描。未验证其他 JNI 平台、网络文件系统锁、同时多进程写和断电恢复。
- SQLite 会话更新只保证完整快照原子性，同会话并发 AI Services turn 需应用串行化；取消不回滚已提交历史。记忆 ID 支持 String/Integer/Long/UUID，异步需显式配置调用方 Executor，不承诺任意 Object ID 或原生异步 JDBC。
- MCP 已覆盖 2025-11-25 HTTP 工具、会话与 SSE 恢复；现代 HTTP 2026-07-28 子集已验收；现代 stdio/WebSocket 已专项验收；原始 2024 双端点、跨进程持久化游标及完整多模态业务等仍待推进；WebSocket 2025-11-25、服务器 ping/roots、WSS 与恢复已新增网络验收；Registry 已按固定基线查询接口验收；stdio 与资源/提示词范围已新增专项验收。直接 MCP Future 取消会关闭 HTTP；AI Services 则保留已启动工具 drain 到结束/超时的上游约定。
- 已运行内部 ZeroPublisher 的官方 Publisher TCK（62 通过、14 项官方 untested），未运行所有适配器的 Subscriber/Processor/Flow TCK，不声明全组件规范认证。有限并发测试不是所有线程调度的证明；类链接仍排除三个可选宿主平台入口。

## 后续范围：跳过 Guardrails

1. 用户明确要求跳过 Guardrails 代码；后续不开展其专项移植、修复或验收，不将其标为已支持。
2. 现有源码及此前已完成的验证结果保留；该范围调整不要求删除源码或拆除已有调用路径。
3. 第六、七阶段已完成 SQLite 向量和会话记忆持久化；后续可推进 MCP/Agentic/其他集成，不再默认推进 Guardrails。
4. 继续保留真实 Java 8、JDK 17 选定回归、官方 Publisher TCK、独立 BOM 与产物审计，以及按检查点提交的约定；不自动跟随上游、推送或建立后台任务。

## 下一阶段：Agentic 移植前审计与 MCP 剩余能力

按 ROADMAP 继续实现并验收，之后推进全部 Agentic 相关能力及逐模块模型/向量库。保持取消、超时、资源释放和不自动重放工具请求；外部真实服务联调需单独授权，Guardrails 继续排除。单个里程碑结束不代表整体目标完成。

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
| `jdk8-m2` / `3edba3ff0` | 第二阶段最终构建、486 个分层用例、产物消费与运行审计完成 |
| `5892eddac` | 第三阶段验收范围（当时 WIP） |
| `3d7c14bcb` | RAG 取消修复、四路径全链路与独立示例（当时 WIP） |
| `jdk8-m3` / `3c5b4b355` | 第三阶段最终 clean 构建、624 个分层用例、消费示例和运行审计完成 |
| `c939a0d9f` | 第四阶段范围与 embedding 上游回归入口（当时 WIP） |
| `be774a97e` | embedding 数据校验/取消修复、HTTP 验收和独立示例（当时 WIP） |
| `jdk8-m4` / `3dcedfd07` | 第四阶段最终 clean 构建、716 个分层用例、HTTP RAG 消费及运行审计完成 |
| `e42da1340` | 第五阶段独立官方 TCK 入口（当时 WIP） |
| `e9fffab93` | 引用释放、prologue demand/取消修复与并发验收（当时 WIP） |
| `jdk8-m5` / `6735feafe` | 第五阶段最终 clean 构建：788 项通过，另列 14 项官方 untested；消费示例/审计通过 |
| `bef62f318` | 按用户要求跳过 Guardrails |
| `a15eec209` | 第六阶段 SQLite 模块/BOM/验收入口（当时 WIP） |
| `9cf6e1f58` | SQLite 事务实现、类型/并发/RAG 验收与跨进程消费 |
| `jdk8-m6` / `adb3edcad` | 第六阶段最终结果：811 项通过，另列 14 项官方 untested；七模块审计与 SQLite 重启消费通过 |
| `e732ea9dd` | 第七阶段会话记忆范围（当时 WIP） |
| `248c8f71e` | SQLite 会话存储、共享事务、取消修复和跨进程工具历史恢复 |
| `jdk8-m7` | 第七阶段最终结果：903 项通过，另列 14 项官方 untested；记忆/RAG 重启消费及审计通过 |

| `0a12fe508` | 第八阶段 MCP 工具子集范围（当时 WIP） |
| `92546d11d` | MCP Java 8 客户端/OkHttp 工具桥接、24 个验收、23 个上游类与独立 BOM 示例 |
| `jdk8-m8` | 第八阶段最终 clean：1085 项通过、14 项官方 untested；八模块审计及 MCP 消费通过 |

| `cc15813e5` | 第九阶段会话/SSE 恢复完整范围（当时 WIP） |
| `17765ebb8` | 共享会话初始化、逐流 GET 恢复、DELETE/取消/所有权与故障验收 |
| `eb2c6f1be` | GET 503 重试预算修复，32 个恢复验收 |
| `04ef31620` | GET Retry-After 的 0/00 等价写法均纳入同一预算 |
| `jdk8-m9` | 第九阶段最终 clean：1117 项通过、14 项官方 untested；会话恢复独立消费和审计通过 |

下一轮先读此文件、`COMPATIBILITY.md` 和 `git status`。一次性转换工具只用于原始快照，不能对已移植源码重跑作为升级方案。

## 第三阶段历史检查点（当时 WIP，现已完成）

- 已从干净的 `jdk8-m2` 恢复；外置盘挂载，约 69 GiB 可用。
- 不新增集成模块，沿用六模块兼容构建；增加确定性本地 RAG 全链路和选定上游回归。
- 此检查点当时尚未验收；当前支持范围以上方最新阶段最终结果为准。

### 第三阶段实现检查点（当时 WIP，现已完成）

- 加入 `RagJdk8Test`：确定性 embedding、多文档分段/index/元数据、过滤/topK/空结果、JSON/文件恢复及删除、文件加载错误、同步/Future/回调/Publisher 提示词注入和来源、记忆原文保留、阻塞 offload 及失败传播。
- 加入 `RagOpenAiJdk8Test`：四种调用路径通过 loopback HTTP/SSE 发送真实 RAG 请求，并验证上下文和 HTTP 调用释放；不依赖真实模型。
- 已复现并修复两个取消断点：检索器 `thenApply` 到 embedding 的回传、AI Services 的异步 memory/augmentation 组合链。补充 Future/Publisher/等待 memory 期间的取消测试，防止继续调用模型或检索器。
- 聚焦验收：15 个 `RagJdk8Test` 在真实 Java 8 下通过；此前完整构建及新增 HTTP/SSE 4 个用例通过。随后已完成含全部新增边界用例的最终 clean 构建，结果见上方和 milestone-3 快照。
- 独立 BOM 的 `RagExample` 已在 Java 8 输出 `Java 8 RAG OK; answer: 7 yuan; source: apple price is 7 yuan.`；关键词向量仅用于验证流程，不代表真实语义质量。

## 第四阶段历史检查点（当时 WIP，现已完成）

- 从干净的 `jdk8-m3` 恢复，外置盘挂载、可用约 69 GiB。
- 沿用六模块生产依赖；新增 embedding 请求/响应、listener、异步/重试等选定上游回归。
- 本阶段目标：loopback Embeddings API 的批量/维度/token usage/错误/超时/取消，HTTP embedding 到检索及 AI Services 的完整闭环，独立 BOM 消费示例。
- 当时支持范围仍以第三阶段验收为准；现已完成第四阶段验收，未使用真实网络模型或服务凭据。

### 第四阶段实现检查点（当时 WIP，现已完成）

- `EmbeddingOpenAiJdk8Test` 新增 19 个真实 Java 8 用例：批量顺序及 token 合并、请求覆写/维度/Base64/headers/listener、错误/重试/超时、全部批次/兄弟任务取消，及 HTTP embedding -> 存储恢复 -> 四条 AI Services HTTP/SSE 路径。
- 已复现并修复：响应 index 被忽略；响应数量与索引/向量缺失未校验；底层 HTTP Future 独立取消导致批量结果一直等待。同步/异步共用响应校验，全部无 index 的兼容提供方仍保留 wire 顺序。
- 新增独立 BOM 的 `HttpRagExample`，使用真实 OpenAI embedding/chat 适配器访问 loopback；显式自定义 OkHttp 时由消费 POM 声明 BOM 管理的 OkHttp 模块。
- 最新 clean reactor 已通过 89 个 Java 8 / 627 个 JDK 17 用例，0 失败/错误/跳过；随后独立消费项目三个示例及运行审计均已通过，详见上方最终结果和 milestone-4 快照。

## 第五阶段历史检查点（当时 WIP，现已完成）

- 从干净的 `jdk8-m4` 恢复；外置盘挂载且约有 69 GiB 可用。
- 新增独立 `reactive-tck` 测试模块，在真实 Java 8 执行官方 TCK 1.0.4 的 PublisherVerification；生产 Reactive Streams API 仍为 1.0.2，不新增生产依赖。
- 使用新 TCK 修复旧版测试问题，不覆盖官方规则、不限制 maxElements、不排除 required 测试。官方标为 untested 的跳过项将单独记录，不计为通过。
- 此检查点当时尚待完成后续验收；现已完成，详见上方最终结果。

### 第五阶段实现检查点（当时 WIP，现已完成）

- 官方 TCK 1.0.4 同线程/异步两组 PublisherVerification：76 项中 62 通过、14 项官方 untested 跳过；无 required/optional 规则跳过、无失败。使用惰性有界源处理超大需求用例，未修改官方规则或限制 maxElements。
- TCK 已复现并修复取消时保留 subscriber 的问题；ZeroPublisher 在取消/终止时清除引用，非法 demand 错误包含规则 3.9 信息。
- Java 8 新增 10 个并发/生命周期验收：100 轮多生产者/多请求者、300 轮终止竞争、onNext 与 fail/cancel 的确定性竞争、终止后 GC、原始事件流 demand/RAG 失败竞争，以及真实 SSE 溢出与 30 轮 request/cancel 释放。
- 修复原始事件 Publisher 在 prologue 尚未交接真实 Subscription 时忽略非正 demand 的问题，以及 onSubscribe 立即取消后仍启动 RAG；终止统一去重，清理 DeferredSubscription 的 delegate/callback 引用。
- 聚焦 Java 8 并发验收通过后，已完成最终 clean 构建与快照。生产依赖/BOM 支持模块数量不变，TCK/TestNG 仅用于独立测试模块。

## 第六阶段历史检查点（当时 WIP，现已完成）

- 用户批准 SQLite 持久化阶段；Guardrails 继续跳过。仓库从干净状态恢复，外置盘挂载、约 69 GiB 可用。
- 新增独立 `langchain4j-sqlite`，兼容 reactor/BOM/验收/消费审计纳入该模块；此检查点当时尚未标记支持，现已完成上方最终验收。
- 首版方案：文件数据库、事务批量 upsert、类型保真元数据、Java 精确余弦全表扫描/topK/过滤和删除；不使用 sqlite-vec 扩展或 ANN 索引。
- 候选 Xerial JDBC 3.53.4.0 的官方 POM 使用 release 8，此检查点当时仍待产物/Java 8/JNI 验收，现已通过。依赖与 native 临时文件均留在外置盘。
- JDBC 为阻塞接口，原生 async 不作虚假实现，异步 RAG 显式 offloadBlocking(true)。

### 第六阶段实现检查点

- `SqliteEmbeddingStore` / `SqliteCodec` 已实现文件路径 builder、维度固定、事务 upsert/删除、只读快照、类型保真编码和精确 topK/过滤；连接按操作关闭，close 等待在途操作且不删除文件。
- 20 个 Java 8 SQLite 用例验证 JNI/重开/所有 metadata 类型/大 Unicode/upsert/空批次/过滤/排序/维度/回滚/损坏/schema/锁/并发及内存参考结果对照；3 个 SQLite RAG 用例覆盖四条 loopback HTTP/SSE 路径和 embedding 阶段取消。
- 首次独立消费验证发现误加的 dependencyManagement 项覆盖了 BOM，已修正；最终 clean 构建通过。驱动有 126 个 Java 8 基础类；GraalVM/Java 9 类仅在多版本目录，不需要新增基础类链接排除。
- `HttpRagExample write/read` 在两个实际 Java 8 进程完成 OpenAI HTTP embedding 入库和重开后的异步 RAG；writer 2 个段落，reader 仅发 1 次查询 embedding，无重新入库。
- 当前最终结果：122 Java 8 / 627 上游 / 62 Publisher TCK 通过；14 项官方 untested 单独列出。31 JAR/5978 基础类审计、4752 类链接通过，保持 3 个既有可选平台排除。最终快照已保存为 milestone-6.json，阶段标记 jdk8-m6。

## 第七阶段历史检查点（当时 WIP，现已完成）

- 从干净的 `jdk8-m6` 恢复；外置盘挂载、约 69 GiB 可用；Guardrails 继续跳过。
- 沿用七模块、SQLite JDBC 3.53.4.0，新增同模块 SqliteChatMemoryStore，复用事务/连接生命周期，不新增生产依赖。
- 计划：类型隔离的稳定会话 ID、核心消息 JSON codec、会话整体事务替换/删除、重启与会话隔离/工具消息/RAG 验收。
- 默认仅同步；调用方显式配置 asyncExecutor 才启用阻塞 JDBC 的异步 offload，Executor 生命周期归调用方。
- 该检查点当时尚未验收；现已完成上方第七阶段最终验收。

### 第七阶段实现检查点

- 新增同模块 SqliteChatMemoryStore：类型隔离 ID、版本化核心消息 JSON、整段事务替换/删除、独立可变读结果、默认同步与显式 Executor offload。抽取 SqliteDatabase 共用连接/事务/close 生命周期，SQLite 向量回归保持通过。
- 新增 27 个真实 Java 8 用例：15 个存储/异步/并发，3 个窗口记忆取消，7 个 AI Services HTTP/SSE/工具/会话，2 个 SQLite RAG+记忆共存；新增 4 个上游消息 codec/类型测试类。
- 测试复现并修复窗口记忆消息投影与 addAsync 组合的取消断点，以及 AI Services 等待记忆组装时不传递取消而继续写入排队问题的情况。只修改记忆链路，Guardrails 逻辑未改动。
- 两个独立 Java 8 进程：memory writer 保存 4 条含工具调用/结果的消息，memory reader 恢复历史后向模型发送 5 条并保存为 6 条，向量及会话共用 DB。
- 完整 clean 构建已通过：149 个 Java 8、692 个 JDK 17、62 个 Publisher TCK；14 项官方 untested 单独列出。31 JAR/5986 基础类审计、4756 类链接通过；最终快照已保存为 milestone-7.json，阶段标记 jdk8-m7。

## 第八阶段历史检查点（当时 WIP，现已完成）

- 从干净的 jdk8-m7 恢复，外置盘挂载、约 69 GiB 可用。Guardrails 继续跳过。
- 审计固定基线 MCP：主要障碍为 JDK HTTP/WebSocket、Flow、record/sealed、新语法和 Java 9+ Future/Atomic API；不新增生产依赖版本。
- 目标：第八个生产模块 langchain4j-mcp，保留客户端/工具 provider/executor，HTTP 改为 OkHttp，首轮只验收 MCP 2025-11-25 的 tools/list、tools/call、JSON/非恢复 POST SSE 和 AI Services 工具循环。
- stdio/WebSocket/Registry 源码保留但不进入兼容产物；modern protocol、GET subsidiary SSE/恢复订阅/自动会话重连和资源/提示词专项验收不在本轮支持声明内。
- 该检查点当时尚未验收；现已完成上方最终 clean 验收。

### 第八阶段实现检查点（当时 WIP，现已完成）

- MCP 112 个生产源码文件已使用 --release 8 编译；默认 HTTP 传输以 OkHttp 替换 JDK HTTP，首轮固定 2025-11-25 工具协议。
- 新增 24 个真实 Java 8 验收用例全部通过：HTTP/SSE、分页/schema、错误、超时/直接取消/close/初始化失败释放、并发 ID、值类 JSON 和四条工具循环。
- 增加 23 个上游 MCP 测试类，共 158 项；JDK 17 选定回归总计 850 项通过，0 失败/错误/跳过。没有删改这些原始测试。
- 新增联调用例最初要求 AI Services 立即取消工具，失败后核对源码确认上游有意 drain 已启动工具以保留补偿结果；保留此约定，最终分别验证直接 MCP 取消与 AI Services 取消后不再调用模型。未修改 Guardrails 或通用补偿逻辑。
- 初始化失败遗留 HTTP 的清理路径已补齐，pending registry 统一终止清理；McpExample 与 BOM 已纳入最终构建，随后完整 clean/消费/运行时审计全部通过，结果见上方和 milestone-8 快照。

## 第九阶段历史检查点（当时 WIP，现已完成）

- 目标：MCP 会话过期重新初始化、显式 DELETE 释放、可选 GET SSE 通道、POST/GET SSE 断线恢复与 Last-Event-ID；并发、取消、超时及资源释放全部验收后提交阶段标签。
- 从干净的 jdk8-m8 恢复，外置盘已挂载、约 69 GiB 可用。继续固定 MCP 2025-11-25；Guardrails 跳过，不连接外部服务器或读取其他凭据。
- 约束：恢复不重发任何原始 POST；失效会话的在途请求明确失败，新请求等待共享初始化。SSE 恢复只 GET 同一流游标；重试有间隔/次数及请求总时限。关闭停止全部重试并以有界 DELETE 尝试释放已知会话。
- 该检查点当时尚未验收；现已通过上方完整 clean 构建、真实 Java 8/上游/TCK、独立 BOM 与产物审计。

### 第九阶段实现检查点（当时 WIP，现已完成）

- 实现会话代际、共享初始化、404 在途失败/后续新请求等待、能力和缓存失效；使用独立负数恢复请求 ID，不重发原 POST。
- 新增 SSE 读取器，支持 UTF-8/BOM、CR/LF/CRLF、完整事件游标提交、retry 字段、空/无效/未完成事件；POST 断线仅 GET 恢复，GET 子通道独立游标/dispatcher、405 降级和有界连续失败重试。
- 新增 terminateSession/有界 DELETE、幂等 close、调用方客户端所有权与自建资源关闭。请求总 deadline 跨恢复生效，取消通知绑定原会话；通过 one-shot body 禁止 OkHttp 503/421 等隐式 POST 重发。
- 聚焦真实 Java 8：新增 31 项恢复验收和原有 24 项 MCP 均已通过；随后继续补强 close 截止与握手并发收尾，并将独立 BOM 的 MCP 示例增加 recovery 模式，随后完整 clean 验收通过，见上方及 milestone-9 快照。
- 记录诊断：成功响应后的 Call.cancel 清理已修正；原生 OkHttp 对照定位另一个 EOF 为 Java 8 HttpServer 空 GET/DELETE 请求体未初始化造成夹具连接关闭，夹具已修复，未以重试 POST 掩盖错误。

### 第九阶段重试边界修复（当时 WIP，现已完成）

- 最终代码核对新增 503 Retry-After:0 的 GET 计数验收：修复前配置 2 次恢复却收到 6 个 GET（预期 3），复现了 OkHttp 自身的隐式跟进。
- GET 网络拦截将该响应交回 transport 的重试调度，单次请求次数与间隔现在受同一策略控制；新增用例通过，MCP 恢复验收增加到 32 项。最终 clean 构建、独立恢复消费和运行时审计随后全部通过。

### 第九阶段最终验收

- `./jdk8/build.sh -o` 以 exit 0 完成；生产模块 release 8，205 项 Java 8（1.8.0_462）/850 项上游（17.0.13）/62 项 Publisher TCK 通过，14 项官方 untested 单列。
- 33 JAR/6294 基础类审计、4910 类 Java 8 成员链接通过，保持三个既有可选平台排除；无新增生产依赖。
- 独立 BOM recovery 示例确认 sessions=2、expired POST=1、executed tools=1、resume GET=1、DELETE=1；所有旧 AI Services/RAG/SQLite 跨进程消费仍通过。
- `verification/milestone-9.json` 逐项列出目标与当前测试证据；进度、兼容差异和模块使用说明已更新。未修改 Guardrails、相邻原始仓库、宿主服务；未推送或发布。


## 第十阶段最终验收

- stdio 生命周期改造和 19 项真实 Java 8 子进程验收完成，HTTP 资源/提示词等 14 项完成；新增独立 BOM 子进程示例。
- 复现并修复资源/提示词中断不取消 HTTP（2 项）和查询途中通知被旧响应覆盖（3 项）；保留确定性回归，关闭期流异常不再逃逸到调用方 executor。
- 最终 clean exit 0：238 Java 8、858 JDK 17、62 TCK 通过；14 官方 untested 单列；33 JAR/6302 基础类审计、4914 类链接和所有消费示例通过。
- 全量模块候选清单只是范围记录，Agentic 与其余模型/向量库尚未实现，不计完成。未修改相邻源码仓库/Guardrails/宿主服务，未推送或发布。


## 第十一阶段最终验收

- Registry 全部六个基线同步 API、模型与 Java 8 语法/API 降级完成；查询参数分别编码、路径单段编码和纯点段校验，headers 不再修改调用方 Map。
- 12 项真实 Java 8 覆盖嵌套数据、时间、分页、headers、32 并发请求、错误/JSON/超时恢复；新增三类 6 项完整上游回归。首轮模型扫描因 JAR CodeSource 失败，测试 harness 使用同次 MCP 生产类目录后原测试通过；独立消费及审计仍用 JAR。
- 最终 clean exit 0：250 Java 8、864 JDK 17、62 TCK 通过；14 官方 untested 单列；33 JAR/6354 基础类审计、4940 类链接和所有旧/新消费通过，无新增生产依赖。
- 固定基线 v0/v0.1 的本地 HTTP 验收不证明公共 Registry 当前互操作；同步接口没有原生 async/请求级取消。整体目标仍未完成，继续 WebSocket/其他协议及后续全部模块。


## 第十二阶段最终验收

- WebSocket 改用现有 OkHttp，真实 Java 8 验证工具、分片/控制帧、WSS、连接代际、共享恢复、取消/时限、缓存刷新、服务器 ping/roots、关闭及资源所有权；无新增依赖。
- 26 项新验收包括 20 在途失败且不重放、20 后续调用共享一握手、单等待者取消不影响共享恢复、握手/恢复失败后的新调用重试、重复 ID 和队列/在途上限。
- 完整 clean exit 0：276 Java 8、864 上游、62 TCK 通过；14 官方 untested 单列；33 JAR/6364 基础类审计、4945 类链接及所有旧/新 BOM 示例通过。WebSocket 示例 wire 次数确认没有重放失效工具。
- 原 WebSocket listener/JDK 类型签名及单参数 TLS/executor builder 有迁移差异，见兼容表；其他协议和剩余 MCP/Agentic/模型/向量库仍未完成，整体目标保持推进。


## 第十三阶段最终验收

- 接通固定基线 2026-07-28 HTTP server/discover、方法/名称/参数头、无旧会话与 subscriptions/listen；默认版本不变。现代 404/取消不触发旧 initialize/DELETE 或重放工具。
- 31 项实际 Java 8 覆盖订阅确认/拒绝/超时/中断/EOF/释放、八个并发长流、版本回退、续传、异步工具当前轮次取消/超时/轮次上限，以及并发响应/确认流绑定。
- 已复现并修复订阅中断泄漏、默认五个单主机长流槽位饥饿、完成回调中错误 busy；同步请求状态续传和工具中断也释放当前请求。确认前 deadline 有界，匹配确认后取消 unary deadline，流并发默认 64 可配。
- 完整 clean exit 0：307 Java 8、864 上游、62 TCK 通过；14 官方 untested；33 JAR/6366 类审计、4946 类链接及全部旧/新消费通过，无新增生产依赖。
- 现代 EOF 新订阅由应用显式发起；交互 inputRequests 沿用基线明确拒绝。旧 2024 这里只验证单端点版本字段，不代替双端点协议支持。其他 MCP、全部 Agentic、模型/向量库与最终交付仍未完成，整体目标保持 active。


## 第十四阶段最终验收

- 现代 WebSocket 与 stdio 共用高层协议：发现、确认型长期订阅、UTF-8、状态续传、取消/超时/中断、旧版本回退和关闭通过实际 Java 8 验收。
- 复现并修复 stdio 服务器取消回显及真实进程重启缓存未失效；现代 WebSocket 恢复、健康探测首先恢复时均刷新能力和自动列表订阅。自动探测后的消息使用协商版本，不再复用空配置版本。
- 39 项新增用例含 20 个等待者共享恢复、观察/单等待者取消隔离、失败恢复后新调用、旧订阅不在新连接取消、资源所有权和进程释放。
- 最终 clean：346 Java 8、864 上游、62 TCK 通过，14 官方 untested；33 JAR/6366 类审计、4946 类链接及所有旧/新独立消费通过，无新增生产依赖。
- 不自动重放失效工具；原始旧 HTTP+SSE 与更多未验收能力、全部 Agentic、模型/向量库仍未完成，整体目标保持 active。


## 技术系列文档（2026-10-22）

新增 [从高版本 JDK 回移植到 Java 8：技术改造系列](docs/JDK8-BACKPORT-SERIES.md)，共十篇正文，包含改造示例、实际回归与验证边界，并在 README 增加入口。已检查内部链接、代码围栏、章节及引用快照统计；两个完整 Java 示例以 JDK 17 release 8 编译并在实际 Java 8 1.8.0_462 执行，验证值语义、构造约束、浅层快照、null 拒绝及不可修改性。本次只修改文档，未重跑全工程构建；正文的 1725 通过/14 官方 untested 引用 4d09bf8b7 既有检查点，不是本次新执行结果。整体工程状态仍为 WIP，支持范围不变。
