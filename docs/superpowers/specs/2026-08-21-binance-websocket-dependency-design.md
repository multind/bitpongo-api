# Binance WebSocket 依赖与官方示例对齐设计

## 目标

将 Binance Spot Connector 升级后的行情 WebSocket 接入严格对齐官方 11.0.1 示例，同时消除当前 POM 中逐项排除和重复声明 Jetty 依赖的复杂配置。

## 方案选择

采用项目级 Jetty 版本覆盖：保留 `io.github.binance:binance-spot:11.0.1` 的正常传递依赖，在 Spring Boot 父工程属性中将 `jetty.version` 固定为 Binance Connector 使用的 `11.0.26`。项目使用 Tomcat 作为 Web 容器，因此该覆盖只服务于 Binance WebSocket 客户端，不引入 Jetty 服务端。

未采用以下方案：

- 继续逐项排除并显式声明全部 Jetty 组件：可用，但容易遗漏新传递依赖，维护成本高。
- 不做任何版本覆盖：Spring Boot 4.1.0 会管理 Jetty 12.1.x，可能与 Binance Connector 基于 Jetty 11 编译的 WebSocket 客户端不兼容。

## 代码调整

- POM 保留 `binance-spot:11.0.1`，新增 `jetty.version=11.0.26`。
- 删除 Binance 依赖上的 Jetty exclusions 以及重复的 Jetty 直接依赖。
- 使用官方 `AllMiniTickerExample` 的调用形式：创建 `AllMiniTickerRequest` 并传给 `allMiniTicker(request)`。
- 保留现有虚拟线程读取、失败回调、关闭逻辑和多交易所抽象，不扩大本次范围。

## 验证

- 编译测试验证 11.0.1 API 调用兼容。
- Maven dependency tree 中所有 `org.eclipse.jetty` 和 `org.eclipse.jetty.websocket` 组件必须解析为 11.0.26，不能混入 12.x。
- 运行行情客户端相关单元测试和项目完整测试。

