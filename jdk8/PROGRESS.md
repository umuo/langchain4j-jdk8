# Java 8 移植进度

基线：7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79

## 初始化（2026-10-20）
- 独立仓库，完整上游历史，无对象共享。
- 五个目标模块、独立构建入口、独立 Maven 坐标和 BOM。
- 状态：全部 WIP，尚未声明 Java 8 可用。
- 临时保留 mutiny-zero 用于原始 Java 17 源码分析，后续替换。

## Core 移植检查点
- 已完成 Java 8 语法/API 转换，Flow 公共类型改为 Reactive Streams。
- 添加不可变集合、失败 Future、延迟执行及新 JDK 反射兼容工具。
- javac 17 `--release 8` 编译 core 通过；独立 Maven core compile 通过。
- 新增真实 Java 8 验收用例，尚待整体验证运行。状态：WIP。

## HTTP 与响应式检查点
- HTTP、OkHttp、reactive-streaming、OpenAI 五模块生产源码整体 `--release 8` 编译通过。
- SPI 默认使用 OkHttp 4.12；移除 mutiny-zero 1.3.1（其基础类为 Java 17 字节码）。
- 内部有界发布器支持需求量、取消、溢出失败、串行信号和终止清理。
- 新增 UTF-8 分片/SSE、需求量/取消/并发、loopback OpenAI 验收；待执行结果。
