# Agentic patterns Java 8 移植（WIP）

全部 15 个基线生产文件进入显式 `agentic-wip` profile；默认八个支持模块与 BOM 不变。主 Agentic 和 patterns 都尚未完成完整能力与独立消费验收，不新增阶段标签。

本单元转换四个 record（公开 Desire、内部 SearchState/StateScore/NodePair）为 final 值类，保留组件访问器、构造校验、equals/hashCode/toString 与字段浅引用语义。Desire 的工厂集合仍不可修改，直接构造不额外复制调用方 List，与基线一致；增加显式 JSON 构造元数据。record 反射/父类差异沿用兼容约定。

Java 9+ 集合工厂、Stream.toList、模式绑定与唯一文本块改为 Java 8；复用 core 的 Jdk8 集合工具。Stream 结果仍不可修改，允许 null 的语义保持；过载搜索/日志调用使用明确的中间 List 以消除 Java 8 类型推断歧义。没有重新运行旧迁移工具或改动已迁移主模块。

`PatternsFoundationJdk8Test` 在真实 Java 8 运行九项基础场景：BDI 意图、Blackboard 数据依赖、GOAP 路径、P2P 下游激活、Voting 并行聚合、Debate 收敛与 judge 上下文、Decision Router 单候选不调用模型、人工响应完成后的 router 结果重算及 Desire 值/工厂合同。首轮 router 用例使用 UntypedAgent，不满足基线明确要求的 typed interface；改为 typed router 后验证原合同，不放宽生产校验。

`patterns-upstream-tests` 在 JDK 17 单独运行全部八个原始基线 Test 类和原始 DecisionModelMock helper，断言不改；不加载 Models 或外部服务 IT。`audit-patterns-tests.sh` 对固定 Git 对象校验九个源文件的内容与选择。`audit-patterns-api.sh` 从固定基线导出 15 个生产源，编译 Java 17 对照产物后与候选 JAR 比较；审计本身不替代功能证明。

验证命令：

```sh
./jdk8/build.sh -o -Pagentic-wip
./jdk8/audit-patterns-api.sh
./jdk8/audit-patterns-tests.sh
```

后续继续全部策略与 IT/端到端覆盖、模型路由/提取、优先级与抢占、并行/异步/流式、挂起持久化、错误/取消/资源释放、声明式组合及独立 BOM 消费。首轮基础场景和原始单元通过不能证明 patterns 全面支持。Agentic MCP/A2A、主 Agentic 剩余能力、MCP 旧双端点、全部待移植模型/向量库仍保留。


首轮完整回归还复现两个原始 DecisionRouterPlannerResumeTest 失败：返回值仍为 `<pending>`。通用终点检查点原本冻结 planner.done(result) 的暂存结果，绕过 router.firstAction 根据 restored deferred 状态重算。现对带非空 planner 结果且无外部 output function 的暂停保留常规执行检查点，由 planner 的原恢复逻辑重算；内置末端 null-result 等待继续使用终点检查点，不重放人工提供者。新增 Java 8 用例使用显式 @MemoryId 复用会话；无 ID 的 ephemeral scope 不作为跨调用恢复夹具。


Patterns 基础检查点完整 clean exit 0：真实 Java 8 430 项（支持模块 346 + 主 Agentic 75 + patterns 9）、JDK 17 1185 项（含 patterns 原始 70 项）、TCK 62 通过/14 官方 untested，共 1677 通过。main/patterns API 与原始测试来源审计通过；patterns 22 类均 classfile 52，无禁止引用，扩展链接 5179 类及既有 BOM 消费通过。快照 verification/agentic-patterns-foundation-wip.json；仍属 WIP，无新阶段标签或 BOM 支持声明。

## 第二个 WIP 单元：异步/流式结果与错误边界

- 首轮两个实际 Java 8 子进程分别复现 Voting 将 `<pending>` 当数字而 ClassCastException，以及 Debate 以占位文本形成 judge 上下文。新增内部兼容方法 AgenticJdk8.invocationOutput，展开被选调用的 async 计算及中间流，DeferredResponse 外部输入标记仍交由 planner 自身的挂起策略处理。
- Voting、Debate 的 position/judge 和 Decision Router 在消费结果之前使用该方法。计算体仍可并行：两个生产者通过 CyclicBarrier 才返回；用例不将后续 planner 看见派发记录等同于计算已经完成。
- 六项新实际 Java 8 子进程：并发数字投票、异步位置与 judge、默认三轮 Debate、异步 router 输出、中间 TokenStream 的两段文本聚合、原始错误原因与一次恢复处理。最后一项最初观测到恢复次数 2 而非 1。
- planner 回调观察到的 AgentInvocationException 通过 AgentExecutor 私有异常标记穿过当前 Agent 的恢复 catch，再在 AgentExecutor 执行边界还原原异常，避免把已处理的计算错误作为当前 Agent 的新失败再次恢复。正常与 skipped 通知共享此边界；不增加公开异常类型或改变原始异常原因链。非 AI 调用登记先检查 ChatMessagesAccess 再求输出，避免登记日志时提前求失败 Future；主模块另增一项直接验证。
- 流式夹具通过 StreamingChatModel SPI 发送两个 partial 和一个 complete，独立 emitter executor 结束并回收；不是 HTTP/SSE 远程互操作证明。全部新增场景在有界 Java 8 子进程执行，超时后终止并等待回收。
- 首轮 Debate 用例错误地把 unanimous（完整字符串相等）当作 unanimousLastWord；按基线策略明确选择末词一致，并另加默认策略三轮/六次调用断言。一次单模块聚焦误用了旧本地 WIP JAR；不计入验收，最终用同次 reactor 和完整 clean 构建验证。
- 外部人工输入在 Voting/Debate 中的完整挂起/恢复、任意轮次 checkpoint、错误补偿与失败历史、取消/生命周期、其他 patterns 的 async/streaming 和独立 BOM 消费仍待完成，不将这六项当作全模块支持。


Patterns 异步检查点完整 clean exit 0：真实 Java 8 437 项（支持模块 346 + 主 Agentic 76 + patterns 15）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1684 通过。原基线 API/来源和生产依赖不变；新增兼容辅助方法单列，main 212 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-patterns-async-wip.json，仍为 WIP，无新阶段标签或 BOM 支持声明。


## 第三个 WIP 单元：Voting 人工输入与检查点

- 最初五项实际 Java 8 恢复场景均超时，由有界子进程终止回收。Voting 只有计数和临时 List，没有可恢复的票与 skipped 状态；外部响应标记不能交给数字聚合策略。
- 票按 Agent ID 保存，另保存已完成 ID（包括 skipped）。firstAction 仅派发未完成投票；若全部已派发完成，直接尝试聚合。受控挂起的 async 消费者只有真正取得结果之后才加入完成集合，因此可按既有方法级恢复规则重入。
- 聚合遇到延迟票时，通过定义的 output key 读取当前 scope 值；持久化后的 marker 与 scope 中响应对象可能是不同 JSON 实例，不能只等待旧 marker。未回答的 SuspendedResponse 发出受控挂起，PendingResponse 的原阻塞合同保留。所有值就绪后才调用用户 strategy；空票仍返回 null。
- executionState/restoreExecutionState 为既有 Planner 默认方法的公开覆盖实现，签名不变；内部状态字段 completed/votes 为兼容分支扩展。顶层执行状态 Map 不可修改，子集合按快照复制，不包含 voter 实例或模型配置。
- API 比较器对继承的 public dispatch 项使用可见 owner 比较，并忽略接口 default 与合法类覆盖之间的关键字差异；参数、返回、泛型、throws 与其他修饰仍比较。声明项仍显式报告两个覆盖方法为新增声明。新增 audit-api-comparator.sh 证明合法覆盖通过，而参数变化/泛型返回变化仍被拒绝，不以放宽签名检查掩盖差异。
- 七项实际 Java 8 场景覆盖同步/异步人工票、重复等待、两个人工票部分完成、async 消费者重入、两个 JVM 的普通票恢复、两个 JVM 的 async 消费者恢复、两个 JVM 的 skipped 状态恢复。文件夹具为单 writer、String memory ID、正常挂起退出；不宣称断电/多 writer 或外部副作用恰好一次。
- Debate 的轮次/人工输入状态、普通失败伴随未完成外部响应时的清理、完整取消/补偿与其他所有未验收范围继续待推进。


Voting 恢复检查点完整 clean exit 0：真实 Java 8 444 项（支持模块 346 + 主 Agentic 76 + patterns 22）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1691 通过。原基线 API 保留，两个 Voting 检查点覆盖声明显式单列；比较器兼容/反向验证和来源/依赖审计通过；main 212 类/patterns 22 类均 classfile 52，扩展链接 5180 类及既有 BOM 消费通过。快照 verification/agentic-voting-recovery-wip.json；完整工程仍 WIP，不新增阶段标签或 Agentic BOM 支持声明。

## 第四个 WIP 单元：Debate 轮次、人工辩论者与 judge

- 新增七项实际 Java 8 场景最初全部失败：恢复结果为 null 或上一轮 debateContext 被清空。原实现仅恢复 currentRound，忽略消息/本轮完成集合/judge 阶段。
- 现保存/恢复 currentRound、lastDebatersMessages、completedInRound（Agent ID）、judgePhase、judgeCompleted 和可选 judgeOutput。本轮完成前只派发缺失步骤；全部位置可用后才判断收敛/轮次上限并生成上下文。skipped 辩论者保留上一轮冻结位置，仍计入当前轮完成集合。
- 人工辩论者的 DeferredResponse 通过定义的 output key 读取当前 scope 值，未回答时受控挂起；人工 judge 已返回 marker 时保存 judgeCompleted，恢复直接读取回答，不重复询问或重跑辩论者。PendingResponse 的原阻塞合同不变。新的状态 Map 不可修改，集合取快照。
- AgenticJdk8 新增公开内部常量 PLANNER_MANAGES_RESUME，值为 __plannerManagesResume。Debate 将该布尔字段写入执行状态，明确由自身恢复完整的 phase/dispatch 集合；通用 Root 的完成 ID 不能区分轮次，不再过滤这个 planner 合法重复调用的 Agent。未声明该内部能力的 planner 继续使用原过滤规则，不改变 Planner 接口。
- 七项场景包含同步/异步人工辩论者、同步/异步人工 judge、第二轮两个尚未成功绑定参数的消费者、跨两个 JVM 的第二轮恢复和跨两个 JVM 的异步人工 judge。第二轮前保留第一轮上下文，最后的 judge 只接收第二轮实际位置；固定执行日志验证第一轮未重放，人工提供者未被额外询问。
- 跨 JVM 使用既有单 writer/String ID 文件夹具和正常挂起退出，不证明任意进程强杀点、多 writer 原子性或外部副作用恰好一次。之前未完整保存这些字段的 WIP Debate 检查点不在新格式的恢复声明内。
- 普通失败伴随未完成外部响应时的清理、全局取消、完整补偿/观测，以及其他 patterns、Agentic MCP/A2A 和整个长期范围继续待推进。


Debate 恢复检查点完整 clean exit 0：真实 Java 8 451 项（支持模块 346 + 主 Agentic 76 + patterns 29）、JDK 17 1185 项、TCK 62 通过/14 官方 untested，共 1698 通过。原基线 API/来源和生产依赖保持；新增内部恢复标记常量单列，main 212 类/patterns 22 类均 classfile 52，无禁止引用；扩展链接 5180 类与既有 BOM 消费通过。快照 verification/agentic-debate-recovery-wip.json；完整工程仍 WIP，不新增阶段标签或 Agentic BOM 支持声明。
