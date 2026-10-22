# 从高版本 JDK 回移植到 Java 8：技术改造系列

> 以 LangChain4j Java 8 兼容工程为例，讨论如何把现代 Java 项目改造成可在 JDK 1.8 上运行、可验证、可持续维护的独立分支。

编写日期：2026-10-22。本文依据本仓库源码、构建脚本和验证记录整理，不要求连接真实模型服务。

固定上游基线：`7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`。案例代码检查点：`4d09bf8b7`。它是固定开发基线的兼容分支，不代表当前官方最新版。

本文中的 Java 8、JDK 8、JDK 1.8 指同一代平台。项目采用 **JDK 17 构建、`--release 8` 编译生产代码、真实 Java 8 JVM 验收**。这是整个系列的前提。

## 系列导读

| 篇目 | 核心问题 | 阅读后的产出 |
| --- | --- | --- |
| 第一篇：定义兼容目标 | 什么情况下才算移植成功？ | 可检查的兼容合同 |
| 第二篇：建立独立工程 | 如何隔离上游、构建与产物？ | 基线、构建入口和支持矩阵 |
| 第三篇：降低语言版本 | record、模式匹配、switch 如何改？ | 保持原有行为的 Java 8 源码 |
| 第四篇：替换基础 API | 集合、字符串、Stream 能否直接替换？ | 有明确语义的兼容辅助层 |
| 第五篇：治理依赖和接口 | 自己编译通过，为什么仍然启动失败？ | 依赖、SPI 和接口差异清单 |
| 第六篇：重建 HTTP 与响应式链路 | 换传输后，流式调用还正确吗？ | 网络、背压和资源释放验证 |
| 第七篇：处理异步与取消 | future 取消，为什么后台仍在执行？ | 按调用登记的任务所有权 |
| 第八篇：保存状态与恢复执行 | 为什么不能直接保存 Future？ | 检查点与方法级恢复合同 |
| 第九篇：建立验收体系 | 如何证明是真的支持 Java 8？ | 分层验证与可追溯证据 |
| 第十篇：复盘与长期维护 | 如何让兼容分支持续演进？ | 回归案例、增量交付与维护流程 |

各篇按“问题 → 改造方法 → 容易遗漏的语义 → 验证方式”展开，可作为团队内部培训材料，也可拆分成技术连载。

---

## 第一篇：定义兼容目标——先写验收合同，再修改代码

### 1.1 修改 Maven 版本号只是起点

把 `source`、`target` 改成 `1.8`，不能证明项目已经兼容 Java 8。至少要分别回答以下问题：

| 兼容层面 | 典型失败 | 应有证据 |
| --- | --- | --- |
| 语言语法 | record、模式匹配不能编译 | 生产源码按 Java 8 编译 |
| 字节码 | 依赖含高版本 class | 运行时 JAR 的基础类版本审计 |
| Java API | 调用 Java 8 不存在的方法 | `--release 8`、API 审计与实际运行 |
| 扩展加载 | SPI 仍引用被移除实现 | 在 Java 8 中触发真实 provider 加载 |
| 对外接口 | Flow 替换后调用方签名不匹配 | 接口差异说明与独立消费项目 |
| 行为与资源 | 流能返回，却泄漏连接 | 本地协议、并发、取消和释放验收 |

还应单独确认数据和协议合同：POJO 能否读写旧 JSON，持久化状态如何恢复，HTTP 请求和响应是否保持预期。

### 1.2 区分构建 JDK 与运行 JDK

本工程使用 JDK 17 驱动 Maven，生产代码设置：

```xml
<properties>
    <maven.compiler.release>8</maven.compiler.release>
</properties>
```

在 JDK 17 上仅设置 `source/target=8`，仍可能编译到该构建环境可见的较新 Java API。`--release 8` 同时约束语言、目标字节码和标准平台 API；它不负责替你检查所有第三方依赖，也不能证明反射路径和资源加载正确。

因此还需要真实 Java 8 JVM。JDK 17 上的回归与 Java 8 上的验收分别统计，两者覆盖不同的失败类型。

### 1.3 用模块和能力定义范围

“OpenAI 模块编译通过”与“该模块所有模型能力已验收”是两个不同结论。对话、Embeddings、工具调用、结构化输出和音视频能力，应分别列出状态。

本工程默认兼容构建包含八个模块；Agentic 主模块与 patterns 使用显式 WIP profile，尚未纳入默认支持 BOM。Guardrails、Spring Boot Starter、Jackson 3 按当前约定排除，其余长期范围仍保留。

本篇交付物应是一张矩阵：**模块、能力、接口差异、运行时要求、验证证据、剩余工作**。详见[模块清单](../MODULE-INVENTORY.md)和[兼容边界](../COMPATIBILITY.md)。

## 第二篇：建立独立工程——让改造可恢复、可对比

### 2.1 固定上游基线

面对持续变化的上游，先记录完整 commit，而不是只写“基于最新版”。否则今天通过的测试，明天可能因为源码漂移而无法复现。

本工程保留独立 Git 历史、使用 `jdk8/main`，相邻官方工作区不参与修改。每个检查点提交代码和证据，完整阶段验收后再创建阶段标签。WIP 提交可以保存进展，但不能因此把模块标记为已支持。

### 2.2 单独建立兼容构建入口

本工程保留原根 POM 用于上游对照，以 [`jdk8/pom.xml`](../pom.xml) 作为兼容入口，并显式列出纳入的模块。

这种方式保留未移植源码，又能避免它们进入默认 reactor。不要靠大量无说明的排除项，让一个看似成功的全仓构建掩盖尚未移植的模块。

产物使用独立坐标：

```text
groupId: dev.langchain4j.jdk8
version: 1.23.0-jdk8-SNAPSHOT
Java 包名: dev.langchain4j.*
```

Maven 坐标与 Java 包名承担不同职责：前者隔离发布和依赖解析，后者保持消费代码的主要命名结构。两套坐标都存在，并不意味着可以在同一个 classpath 混放两套同名 Java 类；消费端应检查依赖树。

### 2.3 缓存不能成为隐形依赖

在本机 Maven 缓存里碰巧存在官方 SNAPSHOT，会掩盖 reactor 缺失和错误坐标。验收要检查生产依赖树及独立消费项目，确认使用本分支构建的兼容产物。

日常可以使用离线构建，但离线成功只证明当前缓存足够，不等于证明空缓存环境可重建。若需要发布级复现，还应使用受控的独立本地仓库验证依赖获取过程；本工程缓存和大量构建数据留在外置盘。

本篇的关键产出是可恢复的工程结构，而不是一次性批量替换脚本。仓库中的迁移工具是一次性辅助工具，不能对已经人工修正的源码反复执行。

## 第三篇：降低语言版本——保留语义比消除编译错误更重要

### 3.1 record 转换为值类

以这个高版本示例为例：

```java
public record Request(String name, int limit) {
    public Request {
        java.util.Objects.requireNonNull(name);
        if (limit < 0) throw new IllegalArgumentException("negative limit");
    }
}
```

Java 8 替代类需要保留构造约束、组件访问器、值相等性和可预期的字符串输出：

```java
// 完整 Java 8 示例：Request.java
import java.util.Objects;

public final class Request {
    private final String name;
    private final int limit;

    public Request(String name, int limit) {
        this.name = Objects.requireNonNull(name);
        if (limit < 0) throw new IllegalArgumentException("negative limit");
        this.limit = limit;
    }

    public String name() { return name; }
    public int limit() { return limit; }

    @Override public final boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || other.getClass() != getClass()) return false;
        Request that = (Request) other;
        return limit == that.limit && name.equals(that.name);
    }

    @Override public final int hashCode() {
        return 31 * name.hashCode() + Integer.hashCode(limit);
    }

    @Override public final String toString() {
        return "Request[name=" + name + ", limit=" + limit + "]";
    }
}
```

不能用“IDE 生成了 equals/hashCode”代替对照验证。尤其要检查浮点数、数组组件、可变引用、构造校验顺序和方法修饰符。record 本身也不会自动深拷贝集合或数组；移植时额外复制，可能改变原来的语义。

JSON 也是单独的合同：无 setter 的 final 字段，可能需要显式构造器和参数元数据。工程中的值类按实际 codec 补充 Jackson 构造/字段注解。Java 8 替代类不再拥有 JVM record 元数据，反射结果的差异必须明确记录。

### 3.2 模式匹配要保持求值次数

高版本示意：

```java
if (load() instanceof String value && !value.isEmpty()) {
    consume(value);
}
```

Java 8 应先保存表达式结果：

```java
Object loaded = load();
if (loaded instanceof String) {
    String value = (String) loaded;
    if (!value.isEmpty()) consume(value);
}
```

如果转换后在判断和强转时各调用一次 `load()`，遇到有状态读取、消费流或并发状态时，第二次可能得到另一个对象。这种改造能够编译，却已经改变程序。

### 3.3 switch、text block 与接口辅助方法

switch 表达式改成传统 switch 时，检查每个分支是否赋值、返回或抛出异常，避免 fall-through；为分支内局部变量保留必要作用域。新增 default 分支也要核对原来的 null 和异常语义。

text block 不能只看显示效果。提示词、JSON 和协议消息应对照实际字符串，检查缩进、转义、UTF-8 及最后一个换行。本工程的 patterns API 审计还检查了提示词注解常量。

Java 8 接口不能保留高版本的 private 辅助方法。可把实现放进包内辅助类，避免为了编译通过而把它改成新的 public 扩展 API。

本篇验收包括：生产编译、值对象行为测试、JSON 往返、字符串常量对照，以及构造器和成员签名审计。

## 第四篇：替换基础 API——相似写法不一定等价

### 4.1 集合的四个维度

替换集合 API 时，逐项确认：**可修改性、快照还是视图、null 约束、重复元素或键的处理**。

| 原调用或替代项 | 容易忽略的行为 |
| --- | --- |
| `List.of` | 不可修改，拒绝 null |
| `List.copyOf` | 不可修改的浅层快照，拒绝 null |
| `Stream.toList` | 不可修改，但可包含 null，不能一概套用拒绝 null 的工厂 |
| `Arrays.asList` | 固定大小，仍允许 set，且由数组支撑 |
| `Collections.unmodifiableList(source)` | 是包装视图，source 后续修改可能可见 |
| 复制后再 unmodifiable | 得到浅层快照；元素对象仍可能可变 |

下面是适用于“拒绝 null 的浅层快照”合同的 Java 8 实现，并非所有集合调用的统一替代：

```java
// 完整 Java 8 示例：CompatLists.java
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CompatLists {
    private CompatLists() { }

    public static <T> List<T> copy(Collection<? extends T> source) {
        List<T> result = new ArrayList<T>();
        for (T item : source) result.add(Objects.requireNonNull(item));
        return Collections.unmodifiableList(result);
    }
}
```

集合工厂与 copy 方法也不能混淆：本工程的 set 工厂拒绝重复项，copySet 允许输入集合出现重复项并去重。Map 工厂不能默默覆盖重复键。迭代顺序也应记录为兼容差异，不能把本分支的实现顺序当作上游保证。

### 4.2 字符串和 Stream

`trim()` 不等于 `strip()`。本工程使用字符码点与 `Character.isWhitespace` 实现相关辅助操作；字符分类仍取决于目标 JVM 的字符数据库，不能据此宣称所有 Unicode 版本行为完全一致。

`stream.collect(Collectors.toList())` 也不保证与 `Stream.toList()` 拥有相同的修改行为。需要不可修改结果时，应明确包装策略，并分别决定是否接受 null。

Optional、ServiceLoader、InputStream 和 CompletableFuture 的新 API，也应按原调用的异常、空结果和调度语义逐项替换。不要把兼容层发展成另一个无文档的通用工具库。

具体实现见 [`Jdk8.java`](../../langchain4j-core/src/main/java/dev/langchain4j/internal/Jdk8.java)。

## 第五篇：治理依赖和公共接口——兼容链路由最薄弱的一环决定

### 5.1 审计真实生产依赖

生产源码使用 release 8，不会把依赖 JAR 重新编译成 Java 8。需要查看传递依赖、基础 class 版本、标准 API 引用、可选平台入口和随包资源。

本工程曾遇到 OpenNLP 版本与默认句子模型不匹配：换成能在 Java 8 加载的库以后，较新的模型资源仍被拒绝。最终选择与旧运行库兼容的历史模型，记录来源，保留新模型用于对照，而不是伪造资源版本。

这说明依赖兼容不仅是 jar 版本问题，还包括数据格式。当前使用的兼容版本只代表本分支验证基线，不是其他项目升级或选型的通用建议。

### 5.2 替换平台 API，要清理整个扩展链

本工程将默认 JDK HTTP Client 路径替换为 OkHttp，保留 HTTP 抽象、base URL、鉴权、超时和自定义客户端能力。除了源码，还要检查 `META-INF/services` 等 SPI 资源，避免 Java 8 启动时仍尝试加载旧 provider。

虚拟线程使用反射探测，并在 Java 8 下回退到平台线程。反射可以避免直接链接缺失类型，但不能凭空复制虚拟线程的调度特性；线程数量、阻塞行为和执行器所有权仍要验收。

### 5.3 有意改变的签名需要明确披露

`java.util.concurrent.Flow` 改为 `org.reactivestreams` 后，接口的概念相近，类型身份却不同。这属于有意的源代码和二进制签名差异；旧二进制消费方不能直接当作同一套接口继续链接。

本工程使用 Reactive Streams 1.0.2，并排除高版本 Flow 适配路径；这是当前依赖和审计方案的选择，不能推导成“所有更高版本依赖都无法在 Java 8 使用”。

对于原本应保持的 API，则执行严格对照：泛型、参数、返回类型、throws、构造器、继承成员、bridge 和直接接口声明都要检查。一次实际审计发现 AsyncResponse 与 StreamingResponse 的直接接口声明变化，最终恢复了原声明，没有放宽比较器。

## 第六篇：重建 HTTP 与响应式链路——连接释放也是功能

### 6.1 普通请求成功，不代表流式链路正确

SSE 可以在任意网络分片边界拆开：UTF-8 字符可能分段，多行 data 可能分段，结束事件也可能紧跟普通事件。解析与状态转换必须基于协议，而非一次 read 对应一个事件的假设。

本地模拟服务应覆盖普通响应、错误响应、慢响应、部分数据、远端 EOF、重连和取消。超时也应按连接、读取、整个调用或协议确认等实际合同区分，不能靠同一个数值代替所有生命周期。

### 6.2 取消需要早于第一个事件

如果只能等首个 token 的上下文提供 handle，响应头都没到时就没有取消入口。根调用已经失败，底层连接仍可能占用 dispatcher 槽位。

本工程在 `Call.execute()` 或 `enqueue()` 之前登记 `Call.cancel()`，正常结束再解除登记。同步、异步和 SSE 都接入相同的资源所有权桥接。关闭的是当前调用，不是共享客户端或调用者执行器。

验证不能在测试 finally 中先执行 `dispatcher.cancelAll()`，再宣称业务代码释放了资源。应先断言 socket 对端观察到 EOF、dispatcher running/queued 归零及等待结果终止，再进行夹具兜底清理。

### 6.3 响应式兼容要保留背压合同

本工程用内部有界 Reactive Streams 桥接替换不能直接用于 Java 8 的桥接依赖。队列有界只是其中一项，还要验证需求量控制、取消、错误传播、信号串行化和终止事件唯一性。

需求不足时，不能无限缓存 token；溢出、完成或失败后，底层调用也要释放。官方 Publisher TCK 提供重要证据，但它不能证明 HTTP 协议互操作或业务工具调用正确。本检查点 TCK 为 62 项通过、14 项官方 untested，后者不能计为通过。

## 第七篇：处理异步与取消——Future 状态不等于执行状态

### 7.1 CompletableFuture.cancel 不保证中断 supplier

对 `CompletableFuture.supplyAsync(...)` 返回值调用 cancel，不能据此证明运行中的 supplier 已经停止。对于支持中断的任务，也只有在代码和底层调用响应中断时，资源才可能及时释放。

本工程为框架异步任务登记取消动作，并跟踪实际执行线程。提交前登记，运行前检查取消，结束前清除线程引用，避免迟到的取消误中断线程池后来复用的任务。

### 7.2 任务应归属于调用，而非输出键

假设先启动任务 A，将 future 写到 `state["x"]`，随后另一个步骤覆盖 x。如果失败收尾只遍历当前 state，就找不到 A。

因此使用调用级任务登记：输出键可以变化，资源所有权仍然保留。一个 memoryId 的失败应取消自己的任务，不能取消其他会话或关闭共享 executor。

Java 无法安全地强制终止任意忽略中断的用户代码。对于仍在执行的旧任务，本工程在带调用绑定的 scope 方法写入处检查取消，阻止它覆盖新状态。直接修改原始 `state()` Map、用户自行创建且未传播上下文的线程，以及外部副作用，不能自动获得同样保证。

### 7.3 并行失败应及时唤醒上层

`CompletableFuture.allOf(...).get()` 等待全部任务完成；其中一个失败，并不会让另一个永不结束的任务自动退出。

本工程改为：全部成功时等待全部结果，首次观察到失败时唤醒 root，再由统一失败边界取消其余登记任务。提交被 executor 拒绝时，也要清理此前已提交的任务。调用者提供 inline executor 时，仍受其同步调度合同约束。

### 7.4 显式传播上下文，避免线程复用污染

ThreadLocal 不会自动正确传播到任意异步链路。需要明确捕获、安装和 finally 恢复，嵌套调用也不能简单 remove 掉外层上下文。

[`CancellationContext`](../../langchain4j-core/src/main/java/dev/langchain4j/internal/CancellationContext.java) 当前传播资源登记者，使默认 SSE 工具回调触发的后续请求仍可取消。它不等于恢复所有 Agentic ThreadLocal；HTTP 回调中的 scope 写入归属和异步工具执行器仍是后续验收范围。

后续演进：2026-10-22 的[回调归属检查点](../verification/agentic-callback-scope-wip.json)已验证默认 OkHttp 显式捕获回调的 scope 代际绑定和迟到写入拒绝。异步工具执行器及自定义传输仍待推进；该记录不改变本文以 `4d09bf8b7` 为基准的统计。随后[executor 上下文检查点](../verification/agentic-executor-context-wip.json)补充了 AI Services 工具执行器与用户 SPI 的上下文组合；任务主动中断和排队取消仍未完成。

## 第八篇：保存状态与恢复执行——检查点保存业务事实

### 8.1 不保存运行时执行对象

Future、线程、socket、锁和包含运行时闭包的对象，不是可跨进程恢复的业务状态。即使 serializer 可以遍历它们的字段，也不能恢复其执行栈和网络连接。

检查点需要保存的是：哪些步骤已完成、哪些响应仍待外部回答、planner 处于哪个阶段，以及恢复后应该重新进入哪个方法边界。

本工程的 async 检查点对顶层 state 和调用输出做归一化：已完成值可展开，未完成或受控挂起的计算使用恢复元数据表达，外部回答 marker 保留其语义。任意容器中嵌套 Future 的情况仍未完成全面验收。

### 8.2 挂起、失败和取消不能混成一种状态

人工输入尚未提供时，受控挂起表示“等待外部条件”；普通失败表示“当前执行失败”；取消表示“不应再继续当前工作”。把人工挂起交给普通错误恢复，会导致重复询问、重试甚至错误补偿。

一个实际回归是：结算到第一个受控挂起就返回，后面的已完成 human provider 仍包在 async 结果中，导致调用者只能看到部分 marker。修复后结算其余 provider，再返回受控挂起，且不把普通失败吞成暂停。

### 8.3 失败收尾保护原始异常

补偿、listener 和持久化都可能再次失败。后来的清理异常不应无说明地替换原始错误。本工程将次级清理异常作为 suppressed 保留，并尝试后续独立清理步骤。

失败收尾也不应为了排空状态而等待未回答的人工 marker。调用者的回答对象不属于框架后台任务，不能靠伪造一个值使流程结束。

### 8.4 恢复粒度必须写清楚

当前 WIP 恢复主要是方法级重入，不是恢复 Java 执行栈。Voting、Debate 等 planner 还需要保存票值、轮次、消息和 judge 阶段，单独记录“某个 agentId 做过”不足以表达多轮流程。

跨 JVM 测试应使用 writer 保存、reader 恢复，并检查执行次数和输入内容。一次成功恢复不能证明任意崩溃点、多个 writer 或外部副作用恰好执行一次。完整失败历史、自动重试和更多生命周期能力仍在推进。

## 第九篇：建立验收体系——每一层证据各司其职

### 9.1 分层而不是只看一个绿色结果

| 验证层 | 本工程做法 | 不能由它单独证明的内容 |
| --- | --- | --- |
| 生产编译 | JDK 17 + release 8 | 第三方 JAR、反射、协议行为 |
| 真实 Java 8 | 独立验收及子进程 | 未执行的业务路径 |
| 上游回归 | JDK 17 选定原始测试 | 上游所有测试、Java 8 运行 |
| 产物审计 | 基础 class、禁止引用、依赖来源 | 所有方法体的完整链接 |
| 链接检查 | 在 Java 8 解析类及成员类型 | 每个分支均已执行 |
| API 对照 | 固定基线与候选比较 | 数据与业务行为完全一致 |
| 协议/资源验证 | loopback HTTP/SSE/stdio/WebSocket | 真实远程服务互操作和模型质量 |
| 独立消费 | BOM 和独立示例 | 所有宿主框架及部署环境 |

### 9.2 多版本 JAR 与可选平台入口

Java 8 不按高版本 JVM 的方式加载 `META-INF/versions/` 条目。审计需要区分基础类与多版本条目、module-info，避免误报，也不能因此忽略实际会加载的高版本基础类。

本工程链接检查明确排除三个未安装宿主平台的可选入口：OkHttp 的 Android/Conscrypt 与 OpenNLP 的 OSGi 集成。排除项及原因可追踪，不把“未装对应平台”写成“所有环境都已验证”。

### 9.3 保存原始测试，验证审计器

不要因为测试失败，就改成只检查“没有抛异常”。本工程保留选定上游测试的来源审计；对本分支新增场景，则要求先复现失败，再修复。

API 比较器本身也有反向夹具：兼容的默认方法覆盖可以接受，但参数或泛型返回变化必须拒绝。比较器不能只打印成员数量，更不能为了让候选通过而广泛忽略差异。

### 9.4 在本仓库执行

在仓库根目录运行默认验收：

```sh
./jdk8/build.sh -o
```

纳入 Agentic WIP 的完整验证：

```sh
./jdk8/build.sh -o -Pagentic-wip
./jdk8/audit-agentic-api.sh
./jdk8/audit-patterns-api.sh
./jdk8/audit-agentic-tests.sh
./jdk8/audit-patterns-tests.sh
```

`-o` 要求缓存已有所需依赖。脚本使用配置好的 JDK 17/8 与外置盘 Maven 仓库；不同机器需按[工程 README](../README.md)配置路径。API 审计命令要求已经生成相应候选 JAR 和消费 classpath，不能只复制命令而忽略前置构建。

### 9.5 本文引用的检查点结果

以下是 `4d09bf8b7` 所记录的既有代码验证结果，本文编写不代表又执行了一次完整构建：

| 套件 | 通过 | 运行环境 |
| --- | ---: | --- |
| 默认支持模块验收 | 349 | Java 8 |
| Agentic 主模块 WIP 验收 | 100 | Java 8 |
| patterns WIP 验收 | 29 | Java 8 |
| 选定上游回归 | 864 | JDK 17 |
| Agentic 上游回归 | 251 | JDK 17 |
| patterns 上游回归 | 70 | JDK 17 |
| Publisher TCK | 62 | Java 8 |
| 合计 | **1725** | 分环境统计 |

另有 **14 项官方 TCK untested**。证据见[验证快照](../verification/agentic-network-cancellation-wip.json)。基础类条目审计包含 reactor 与已安装依赖中的物理 JAR，并不等于同数量的唯一类；运行时链接结果单独统计。

## 第十篇：复盘与长期维护——把修复积累成工程能力

### 10.1 四个值得保留的回归案例

| 问题 | 旧行为 | 改造要点 |
| --- | --- | --- |
| 覆盖输出键后丢失任务 | root 失败仍有后台任务执行 | 按调用登记所有权 |
| 并行任务先失败 | 等待慢任务，甚至被后续错误遮盖 | 首次观察失败唤醒 root，取消其余任务 |
| 取消发生在响应头前 | 没有首事件 handle，连接继续占用 | 提交 HTTP 调用前登记取消 |
| 首个 consumer 受控挂起 | 后续已完成 human marker 不可见 | 结算其余 provider 后再返回挂起 |

对应代码检查点：`c4093e666`、`891b14c0a`、`4d09bf8b7`。这些是 WIP 增量，不是 Agentic 全能力支持标签。

### 10.2 每个增量使用相同交付模板

1. 固定问题和改造范围，保留可复现的失败证据。
2. 修改语义完整的最小单元，避免把无关重构混在一起。
3. 运行对应 Java 8 场景及必要回归；涉及公共合同或共享基础层时扩大验证。
4. 更新接口差异、依赖树、产物和消费验证。
5. 写明命令、运行环境、结果、排除项及未覆盖能力。
6. 提交可审查增量；完整阶段满足门槛后再创建阶段标签。

可以复用下面的记录格式：

```text
问题与触发条件：
改造前证据：
最终行为与实现边界：
接口、数据和协议差异：
生产依赖变化：
验证命令、JVM、通过/失败/未覆盖：
结果快照与提交：
剩余工作及下一步：
```

### 10.3 自动转换只负责第一遍

AST 工具适合批量定位和机械转换，适合辅助检查 record、模式匹配与 text block。涉及求值次数、相等性、序列化和资源生命周期的地方，需要人工审查与行为测试。

工具执行完成不是移植完成。源码已被人工修复后，重新执行非幂等迁移工具还可能覆盖正确实现。本工程明确把迁移工具定位为一次性辅助，不作为持续升级策略。

### 10.4 当前工程仍未结束

上一完整阶段是 `jdk8-m14`。Agentic 主模块与 patterns 仍为 WIP；Agentic MCP/A2A、更多生命周期能力、MCP 剩余协议、模型与向量库集成以及后续 BOM/消费交付，继续按长期目标推进。

后续演进：工具 supplier 已增加 root 归属、中断和排队取消，9 项基础测试与新增 2 项实际 Java 8 SSE 场景通过，详见 [工具任务取消 WIP 快照](../verification/agentic-owned-task-wip.json)。完整 clean 为 1747 通过、14 项官方 TCK untested；受影响 core helper 严格 API 对照明确报告新增 public @Internal supplyAsync。本文前面的固定历史统计保持原有证据范围。

委托异步工具的 root 取消随后补齐：8 项实际 Java 8 测试包含本地 HTTP 正常工具续传和取消后不重试，详见 [委托工具 WIP 快照](../verification/agentic-delegated-tool-wip.json)。该轮完整 clean 为 1755 通过、14 项官方 TCK untested；没有新增公开声明或生产依赖。

单次工具组合 Future 的主动取消随后补齐：8 项实际 Java 8 测试覆盖排队跳过、运行中断、迟到 Future、取消升级和同步取消策略，详见 [单次工具取消快照](../verification/agentic-tool-cancellation-wip.json)。最终 clean 为 1763 通过、14 项官方 TCK untested。新建的 ToolService 基线 API 对照还发现四个旧 record 替代类的 12 个值方法漏 final；修复后 5 个可见类型、89 个声明成员和 117 个继承公开条目严格对照通过。这个实例再次说明编译和功能测试通过并不自动证明声明一致。

高层 Future/Publisher 到工具轮次的默认取消随后补齐：16 项实际 Java 8 HTTP/SSE 用例覆盖排队/运行/委托工具、注解原始 Future、调用隔离、独立取消与补偿合同，详见 [高层工具轮次取消快照](../verification/agentic-tool-loop-cancellation-wip.json)。显式启用补偿时继续等待已启动工具的成功结果并回滚，避免把结果取消掉之后无从补偿；默认模式则 best-effort 取消活动工具。最终 clean 为 1779 通过、14 项官方 TCK untested；ToolService 与 DefaultToolExecutor 的局部严格 API 对照通过。

同轮双工具的后续验收又发现，高层 Future 虽然取消了其他工具，provider 的清理异常仍可能在包装层之间丢失。修复通用取消传播工具后，10 项双工具 HTTP/SSE 场景和 4 项异常传播用例通过；其中包括运行/排队 sibling、结果 ID 对应和补偿逆序，详见 [同轮多工具取消快照](../verification/agentic-tool-fanout-cancellation-wip.json)。最终 clean 为 1793 通过、14 项官方 TCK untested，无新增公开成员、生产类型或依赖。

完成响应 executor 随后接入统一任务登记：8 项实际 Java 8 SSE 用例覆盖排队/运行取消、上下文与失败通道，新增 1 项 Agentic 用例证明终端回调继续使用捕获的旧代际，详见 [完成响应 executor 快照](../verification/agentic-completion-executor-wip.json)。最终 clean 为 1802 通过、14 项官方 TCK untested。TokenStream 的局部公开 API 对照无增删；新增的内部公开 helper runWithOwner 单独记录。该路径使用低层参数中的显式 executor，没有新增高层配置入口。

terminal TokenStream 随后在延迟 start 时恢复创建时的 root owner：14 项实际 Java 8 Agentic SSE 场景和 6 项直接 SPI 场景通过，覆盖取消、代际隔离和正常/错误终止。排空后断言复现了错误回调之后又成功完成的问题，OpenAI 请求现以私有原子状态限制一次终止，并在错误时释放解析连接。最终 clean 为 1822 通过、14 项官方 TCK untested，无新增公开成员、生产类型或依赖，详见 [terminal 流快照](../verification/agentic-terminal-stream-wip.json)。这组网络夹具不含工具。

下一组检查包括 terminal 提前工具执行/续传与 handle 主动取消组合，root 前后 listener 和持久化失败收尾，以及自定义模型/传输与未验收的 CompletionStage 适配器。成功调用被覆盖任务的收尾、完整失败历史和自动重试也尚未完成。

技术文章应把“已证明的能力”和“接下来的设计”分开：可以总结迁移方法，不能用一次编译、一次 clean 或一个漂亮的测试总数代替全部范围的验收。

## 仓库阅读索引

- [工程说明与使用方式](../README.md)
- [当前进度](../PROGRESS.md)
- [兼容差异与验证边界](../COMPATIBILITY.md)
- [模块清单](../MODULE-INVENTORY.md)
- [Agentic 移植记录](../AGENTIC-PORT.md)
- [patterns 移植记录](../PATTERNS-PORT.md)
- [生产构建入口](../pom.xml)与[构建脚本](../build.sh)
- [基础 API 兼容辅助层](../../langchain4j-core/src/main/java/dev/langchain4j/internal/Jdk8.java)
- [OkHttp 实现](../../http-clients/langchain4j-http-client-okhttp/src/main/java/dev/langchain4j/http/client/okhttp/OkHttpClient.java)
- [任务所有权实现](../../langchain4j-agentic/src/main/java/dev/langchain4j/agentic/internal/InvocationTasks.java)
- [严格 API 比较器](../audit/AgenticBinaryApi.java)
- [本文引用的代码验证快照](../verification/agentic-network-cancellation-wip.json)
