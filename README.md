# Bitpongo API

[English](README.md) | [简体中文](README_zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-26-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F.svg)](https://spring.io/projects/spring-boot)

Bitpongo API is the backend service for the Bitpongo automated investment platform. It provides account management, exchange connections, scheduled strategies, order reconciliation, portfolio data, WebSocket prices, and Bark notifications.

The service is built for self-hosting and does not custody user assets. Exchange credentials remain under the user's exchange account and should always be created without withdrawal permission.

> [!WARNING]
> Automated trading involves substantial risk. This software is provided for technical and educational purposes and does not constitute investment advice. Test every strategy with exchange testnet credentials before enabling live trading.

## Features

- REST and WebSocket APIs compatible with the Bitpongo frontend.
- Binance Spot integration through the official connector.
- Persistent scheduled execution with Quartz and MySQL.
- Deterministic client order IDs, idempotent execution, and uncertain-order reconciliation.
- Per-strategy IANA execution time zones and per-user display time zones.
- Encrypted Bark credentials, user notifications, administrator alerts, and an outbox dispatcher.
- Flyway migrations and Spring Boot Actuator health diagnostics.

## Technology

- Java 26 and Spring Boot 4.1.0
- MySQL 9, Flyway, and Quartz Scheduler
- Binance Spot Connector
- Maven Wrapper and Docker Compose

## Related repositories

| Project | Repository |
| --- | --- |
| Web frontend | [multind/bitpongo](https://github.com/multind/bitpongo) |
| Documentation | [multind/bitpongo-doc](https://github.com/multind/bitpongo-doc) |

## Requirements

- JDK 26
- Docker with Docker Compose, or an existing MySQL instance

The Maven Wrapper is included, so a separate Maven installation is not required.

## Quick start with Docker Compose

Copy the environment template and replace every placeholder with a unique secret:

```bash
cp .env.example .env
openssl rand -base64 32
docker compose up -d --build
docker compose ps
./scripts/smoke-test.sh http://localhost:8000
```

Use the generated Base64 value as `BARK_CREDENTIAL_ENCRYPTION_KEY`. Generate a separate random value of at least 32 characters for `JWT_SECRET_KEY`.

The API is available at `http://localhost:8000`. Health endpoints:

```bash
curl http://localhost:8000/health
curl http://localhost:8000/actuator/health
```

Compose creates the shared `bitpongo-net` network used by the separately deployed frontend. Start the API stack before the frontend stack.

## Local development

Start MySQL, configure `.env`, and run:

```bash
export JAVA_HOME=/path/to/jdk-26
set -a
source .env
set +a
./mvnw spring-boot:run
```

Run the verification suite:

```bash
./mvnw clean verify
```

## Configuration

The complete template is available in [`.env.example`](.env.example).

| Variable | Purpose | Safe default |
| --- | --- | --- |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | MySQL application credentials | Required |
| `MYSQL_ROOT_PASSWORD` | MySQL administrator password used by Compose | Required |
| `JWT_SECRET_KEY` | JWT signing secret, at least 32 characters | Required |
| `BINANCE_LIVE_TRADING` | Enables production order submission | `false` |
| `MARKET_STREAM_ENABLED` | Enables the Binance market stream | `false` |
| `BARK_CREDENTIAL_ENCRYPTION_KEY` | 32-byte Base64 key for user Bark credentials | Required |
| `BARK_ADMIN_PUSH_URL` | Optional administrator Bark endpoint | Empty |
| `BARK_ALLOWED_HOSTS` | Bark destination allowlist | `api.day.app` |
| `APP_PUBLIC_URL` | Public link appended to notifications | Empty |
| `BACKEND_CORS_ORIGINS` | Allowed browser origins | Local development origins |

Never commit `.env`, API keys, Bark device keys, passwords, JWT secrets, or production URLs containing credentials.

## Exchange safety

Live Binance trading is allowed only when all of the following are true:

1. Testnet order submission and reconciliation have been verified.
2. `BINANCE_PRODUCTION_REST_BASE_URL` is the official production endpoint.
3. `BINANCE_LIVE_TRADING=true` is set explicitly.
4. Exchange API withdrawal permission is disabled.

Timeouts, connection failures, and Binance 5xx responses are treated as uncertain results. The service reconciles by client order ID instead of blindly resubmitting an order.

## Time-zone model

- Absolute database and API timestamps represent UTC instants and include `Z` or an explicit offset.
- `schedule_timezone` controls when a strategy executes.
- The user display time zone controls formatting only and never changes the scheduled instant.
- Notifications distinguish scheduled time from actual event time.

## Bark notifications

Users may configure individual Bark endpoints. Administrator notifications are configured separately through environment variables. URLs and device keys are treated as secrets and are never returned in full by the API.

Generate the credential encryption key with:

```bash
openssl rand -base64 32
```

See the [Bitpongo documentation](https://github.com/multind/bitpongo-doc) for the user setup guide.

## Docker image

The Compose configuration builds and tags `docker.io/corbettzhang/bitpongoapi:latest`.

```bash
docker compose build
docker compose push
```

## Database upgrades

Flyway manages schema changes. Back up MySQL before every production upgrade and never edit `flyway_schema_history` manually. Validate login, exchanges, strategies, Quartz triggers, orders, notifications, and time-zone data before enabling live trading after a migration.

## Contributing

1. Create a focused branch.
2. Add or update tests for behavioral changes.
3. Run `./mvnw clean verify`.
4. Do not include credentials, personal data, generated secrets, or production logs.
5. Describe behavior, database impact, and deployment considerations in the pull request.

## License

Released under the [MIT License](LICENSE).
