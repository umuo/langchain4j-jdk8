# LangChain4j Java 8 兼容工程

基于官方提交 `7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79`（1.23.0 开发基线）的独立兼容分支。保留完整上游历史、Java 包名和主要模型 API，使用独立 Maven 坐标，按阶段提交。

## 首期产物

以下模块已完成 Java 8 生产源码移植，构建版本统一为 `1.23.0-jdk8-SNAPSHOT`：

- `langchain4j-core`
- `langchain4j-http-client`
- `langchain4j-http-client-okhttp`
- `langchain4j-reactive-streaming`
- `langchain4j-open-ai`

这是固定提交的兼容分支，不代表持续同步官方最新版本。AI Services 的 `langchain4j` 主模块、Agentic、MCP、具体向量库、Spring Boot 集成和 Jackson 3 尚未纳入兼容构建。core 中已有的记忆/RAG 接口随 core 编译，但完整高层流程属于后续阶段。

## 构建与验收

在仓库根目录执行：

```sh
./jdk8/build.sh
```

脚本检查 `/Volumes/Nas` 挂载，使用现有 JDK 17 构建生产代码（`--release 8`），使用真实 JDK 8 执行独立验收，再用 JDK 17 执行选定的上游测试。成功后安装到外置盘 Maven 仓库、编译独立 BOM 消费示例，审计生产 JAR 并在 Java 8 下执行类链接检查和 loopback 示例。

可通过 `JAVA17_HOME`、`JAVA8_HOME`、`JDK8_MAVEN`、`JDK8_SETTINGS`、`JDK8_REPOSITORY` 覆盖本机工具路径。默认复用 SDKMAN 的现有 JDK，缓存位于 `/Volumes/Nas/envs/maven/repository`，不会安装或切换全局 JDK。

独立 Maven 入口是 `jdk8/pom.xml`；**原始根 POM 为上游对比保留，不是兼容构建入口**。标准构建请使用脚本以确保运行了 Java 8 验收和上游回归。手动执行 Maven 时须设置 `-Djava8.home=/path/to/jdk8`。

## 在 Java 8 项目中使用

先执行上述构建以安装本地产物，再导入兼容 BOM：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.langchain4j.jdk8</groupId>
            <artifactId>langchain4j-bom</artifactId>
            <version>1.23.0-jdk8-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>dev.langchain4j.jdk8</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
    </dependency>
</dependencies>
```

Java 导入仍为 `dev.langchain4j.*`。默认 HTTP SPI 已选择 OkHttp，无需引入 JDK HTTP Client。不要在同一应用中混用官方 `dev.langchain4j` 和兼容版同名模块。

`jdk8/example` 是不继承本工程父 POM 的独立消费项目，使用本地模拟服务，无需 API Key。真实服务联调应自行显式提供地址、模型名和密钥，本工程不会读取宿主机服务配置。

## 持续推进

阅读 `PROGRESS.md` 查看已验证结果、检查点提交和下一阶段；阅读 `COMPATIBILITY.md` 查看 API 差异和验证边界。开发者请遵循根目录 `AGENTS.md`。本仓库尚未推送或发布到公共 Maven 仓库。
