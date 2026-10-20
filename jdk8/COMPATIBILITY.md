# 兼容差异与验证边界

## 公共接口

- 包名保持 `dev.langchain4j.*`；Maven groupId 为 `dev.langchain4j.jdk8`，版本统一 `1.23.0-jdk8-SNAPSHOT`。
- `java.util.concurrent.Flow.Publisher/Subscriber/Subscription` 改为 `org.reactivestreams` 对应接口。这是有意的源代码和二进制签名差异；回调式流式 API 保留。
- record 转为普通 final 值类，保留组件访问器、构造校验、equals/hashCode/toString，并为 JSON 添加显式构造/字段元数据。它们不再是 JVM record，反射 `isRecord()` 结果会不同。
- sealed/non-sealed/permits 声明移除，Java 8 无法强制限定继承；Java 17 消费者自行声明的 sealed 类型仍通过反射探测支持。Java 8 多态 POJO 使用 `@JsonSubTypes` 等显式注解。
- `@Deprecated` 的 since/forRemoval 元数据去除；弃用标记保留。
- 自定义集合工厂保留拒绝 null、拒绝重复 Map/Set 工厂项和不可修改的语义；`copySet` 允许输入重复项。集合迭代顺序可能与官方 JDK 工厂不同。

## HTTP、响应式与线程

- OpenAI 默认传输改为 OkHttp 4.12.0；保留自定义客户端 builder、base URL、超时和请求配置。JDK HTTP Client 不进入生产依赖。
- OkHttp 的 SSE 读取在 dispatcher 线程执行，与 JDK 客户端的线程模型不同；Java 8 不承诺无阻塞网络读取。取消、完成、失败或缓冲溢出会释放 HTTP 调用。
- `mutiny-zero` 1.3.1 的 Java 17 类替换为内部有界 Reactive Streams 桥接，覆盖需求量、取消、溢出、串行分发和单次终止；尚未宣称通过完整 Reactive Streams 官方 TCK。
- Reactive Streams 使用 1.0.2 的稳定接口；1.0.4 附带引用 Java 9 Flow 的适配类，不引入 Java 8 生产依赖。
- 保留虚拟线程反射探测和普通线程回退；JDK 8 使用平台线程。

## 依赖与验证

- 验证基线：Jackson 2.18.6、SLF4J 2.0.17、JSpecify 1.0.0、JTokkit 1.1.0、OkHttp 4.12.0。后续升级需重新审计 API、字节码与消费项目。
- 所有移植生产源码以 JDK 17 `--release 8` 编译。JAR 审计检查基础类版本、禁止类型引用和官方模块泄漏；多版本 JAR 的 `META-INF/versions/` 与 `module-info.class` 按 Java 8 加载规则排除。
- 真实 Java 8 链接检查加载全部基础运行时类及其成员类型；仅排除 OkHttp 的两个可选 Android/Conscrypt 类，因本环境不安装对应平台。这不是对全部方法体的穷尽链接证明。
- JSpecify 含 `ElementType.MODULE` 元数据，Java 8 API 编译时有两个注解警告；Java 8 运行和测试已验证通过。SLF4J 默认无日志 provider，消费应用可自行配置兼容的日志实现。
- Java 8 独立验收与 JDK 17 上游选定回归分开执行。上游完整测试集及 Kotlin 测试尚未全量迁移或运行，不以过滤后的结果声称全量通过。
- 已验证 OpenAI Chat Completions 的同步、异步、回调/响应式流式请求及错误、超时和取消；工具定义/调用结果消息和结构化格式已验证请求与响应转换。AI Services 的自动工具执行循环未纳入首期。
- OpenAI 模块其余能力（Responses API、音视频、图像、真实 embedding、真实服务行为）虽然随模块编译及类加载通过，尚未完成独立业务验收。测试仅使用 loopback 模拟服务，无外部密钥。
