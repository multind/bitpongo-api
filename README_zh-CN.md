# Bitpongo API

[English](README.md) | [简体中文](README_zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-26-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F.svg)](https://spring.io/projects/spring-boot)

Bitpongo API 是 Bitpongo 自动化投资平台的后端服务，提供账号管理、交易所连接、定时策略、订单对账、持仓数据、WebSocket 行情和 Bark 通知等能力。

本项目面向自托管场景，不托管用户资产。交易所凭据仍受用户自己的交易所账号控制，创建 API 密钥时必须关闭提现权限。

> [!WARNING]
> 自动化交易存在重大风险。本软件仅用于技术和教育目的，不构成投资建议。启用真实交易前，必须先使用交易所 Testnet 凭据验证所有策略。

## 功能

- 与 Bitpongo 前端兼容的 REST 和 WebSocket API。
- 通过官方连接器集成 Binance Spot。
- 使用 Quartz 和 MySQL 持久化策略调度。
- 确定性客户端订单 ID、幂等执行和不确定订单对账。
- 策略级 IANA 执行时区和用户级显示时区。
- Bark 凭据加密、用户通知、管理员告警和 Outbox 分发。
- 使用 Flyway 管理数据库升级，并通过 Spring Boot Actuator 提供健康诊断。

## 技术栈

- Java 26 与 Spring Boot 4.1.0
- MySQL 9、Flyway 与 Quartz Scheduler
- Binance Spot Connector
- Maven Wrapper 与 Docker Compose

## 相关仓库

| 项目 | 仓库 |
| --- | --- |
| Web 前端 | [multind/bitpongo](https://github.com/multind/bitpongo) |
| 文档站点 | [multind/bitpongo-doc](https://github.com/multind/bitpongo-doc) |

## 环境要求

- JDK 26
- Docker 与 Docker Compose，或已有的 MySQL 实例

项目已包含 Maven Wrapper，不需要单独安装 Maven。

## 使用 Docker Compose 快速启动

复制环境变量模板，并将所有占位值替换为独立密钥：

```bash
cp .env.example .env
openssl rand -base64 32
docker compose up -d --build
docker compose ps
./scripts/smoke-test.sh http://localhost:8000
```

将生成的 Base64 值填写到 `BARK_CREDENTIAL_ENCRYPTION_KEY`。同时为 `JWT_SECRET_KEY` 生成一个至少 32 个字符的独立随机值。

API 默认地址为 `http://localhost:8000`。健康检查：

```bash
curl http://localhost:8000/health
curl http://localhost:8000/actuator/health
```

Compose 会创建供前端独立部署使用的共享网络 `bitpongo-net`。请先启动后端，再启动前端。

## 本地开发

启动 MySQL、配置 `.env` 后执行：

```bash
export JAVA_HOME=/path/to/jdk-26
set -a
source .env
set +a
./mvnw spring-boot:run
```

运行完整验证：

```bash
./mvnw clean verify
```

## 配置

完整模板见 [`.env.example`](.env.example)。

| 变量 | 用途 | 安全默认值 |
| --- | --- | --- |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | MySQL 业务账号 | 必填 |
| `MYSQL_ROOT_PASSWORD` | Compose 使用的 MySQL 管理员密码 | 必填 |
| `JWT_SECRET_KEY` | JWT 签名密钥，至少 32 个字符 | 必填 |
| `BINANCE_LIVE_TRADING` | 开启生产环境下单 | `false` |
| `MARKET_STREAM_ENABLED` | 开启 Binance 行情流 | `false` |
| `BARK_CREDENTIAL_ENCRYPTION_KEY` | 加密用户 Bark 凭据的 32-byte Base64 密钥 | 必填 |
| `BARK_ADMIN_PUSH_URL` | 可选的管理员 Bark 地址 | 空 |
| `BARK_ALLOWED_HOSTS` | Bark 目标主机白名单 | `api.day.app` |
| `APP_PUBLIC_URL` | 通知中附带的公开链接 | 空 |
| `BACKEND_CORS_ORIGINS` | 允许的浏览器来源 | 本地开发来源 |

不要提交 `.env`、API Key、Bark Device Key、密码、JWT Secret 或包含凭据的生产地址。

## 交易所安全

只有同时满足以下条件才允许开启 Binance 真实交易：

1. 已验证 Testnet 下单和对账流程。
2. `BINANCE_PRODUCTION_REST_BASE_URL` 是官方生产地址。
3. 显式设置 `BINANCE_LIVE_TRADING=true`。
4. 交易所 API 已关闭提现权限。

超时、连接异常和 Binance 5xx 都会按“结果不确定”处理。服务会根据客户端订单 ID 对账，不会盲目重复下单。

## 时区模型

- 数据库和 API 的绝对时间表示 UTC instant，并包含 `Z` 或明确偏移。
- `schedule_timezone` 决定策略何时执行。
- 用户显示时区只影响格式化，不改变计划执行的 instant。
- 通知会区分计划触发时间和实际事件时间。

## Bark 通知

用户可以配置各自的 Bark 地址，管理员通知通过独立环境变量配置。URL 和 Device Key 均作为 Secret 处理，API 不会返回完整值。

生成凭据加密密钥：

```bash
openssl rand -base64 32
```

用户配置教程见 [Bitpongo 文档仓库](https://github.com/multind/bitpongo-doc)。

## Docker 镜像

Compose 构建并标记 `docker.io/corbettzhang/bitpongoapi:latest`。

```bash
docker compose build
docker compose push
```

## 数据库升级

数据库结构由 Flyway 管理。每次生产升级前必须备份 MySQL，不要手工修改 `flyway_schema_history`。迁移后，应在开启真实交易前核对登录、交易所、策略、Quartz Trigger、订单、通知和时区数据。

## 参与贡献

1. 创建范围清晰的分支。
2. 行为变化需要新增或更新测试。
3. 运行 `./mvnw clean verify`。
4. 不要提交凭据、个人数据、生成的密钥或生产日志。
5. Pull Request 中说明行为变化、数据库影响和部署注意事项。

## 许可证

本项目采用 [MIT License](LICENSE)。
