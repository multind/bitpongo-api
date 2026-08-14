# Python—Java 兼容契约矩阵

所有 JSON 业务响应继续使用 `{code,message,data}`，字段保持 snake_case。认证端点使用 Bearer Token；价格 WebSocket 保持匿名可连接。

| 类别 | Python 来源与方法 | Java 实现 | 契约测试 |
|---|---|---|---|
| 基础 | `app/main.py` `GET /` | `RootController.root` | `RootControllerTest` |
| 基础 | `app/main.py` `GET /health` | `RootController.health` | `RootControllerTest` |
| 用户 | `users.py` `POST /api/users/login` | `UserController.login` | `UserControllerContractTest` |
| 用户 | `users.py` `POST /api/users/register` | `UserController.register` | `UserControllerContractTest` |
| 用户 | `users.py` `GET /api/users/profile` | `UserController.profile` | `UserControllerContractTest` |
| 用户 | `users.py` `POST /api/users/v1/login` | `UserController.wordpressLogin` | `UserControllerContractTest` |
| 用户 | 移动端新增 `DELETE /api/users/account` | `UserController.deleteAccount` | `UserControllerContractTest` |
| 用户 | `users.py` `POST /api/users/ding` | `NotificationController.ding` | `NotificationControllerContractTest` |
| 用户 | `users.py` `GET /api/users/notices` | `NotificationController.notices` | `NotificationControllerContractTest` |
| 交易所 | `exchanges.py` `GET /api/exchanges/list` | `ExchangeController.list` | `ExchangeControllerContractTest` |
| 交易所 | `exchanges.py` `GET /api/exchanges/{exchange_id}` | `ExchangeController.detail` | `ExchangeControllerContractTest` |
| 交易所 | `exchanges.py` `POST /api/exchanges/create` | `ExchangeController.create` | `ExchangeControllerContractTest` |
| 交易所 | `exchanges.py` `PUT /api/exchanges/{exchange_id}` | `ExchangeController.update` | `ExchangeControllerContractTest` |
| 交易所 | `exchanges.py` `DELETE /api/exchanges/{exchange_id}` | `ExchangeController.delete` | `ExchangeControllerContractTest` |
| 交易所 | `exchanges.py` `POST /api/exchanges/check` | `ExchangeController.check` | `ExchangeControllerContractTest` |
| 交易所 | `exchanges.py` `POST /api/exchanges/minimumAmount` | `ExchangeController.minimumAmount` | `ExchangeControllerContractTest` |
| 策略 | `strategies.py` `POST /api/strategies/create` | `StrategyController.create` | `StrategyControllerContractTest` |
| 策略 | `strategies.py` `GET /api/strategies/list/active` | `StrategyController.active` | `StrategyControllerContractTest` |
| 计划 | `plans.py` `GET /api/plans/list/active` | `PlanController.active` | `PlanControllerContractTest` |
| 计划 | `plans.py` `GET /api/plans/{plan_id}` | `PlanController.detail` | `PlanControllerContractTest` |
| 计划 | `plans.py` `GET /api/plans/{plan_id}/{plan_status}` | `PlanController.updateStatus` | `PlanControllerContractTest` |
| WebSocket | `price_ws.py` `/api/ws/price` | `PriceWebSocketHandler` | `PriceWebSocketContractTest` |
| 定投 | `jobs.py` `plan_scheduled_task` | `PlanPurchaseJob` / `ScheduledPurchaseService` | `ScheduledPurchaseServiceTest` |
| 快照 | `jobs.py` `asset_snapshot_scheduled_task` | `AssetSnapshotJob` / `AssetSnapshotService` | `AssetSnapshotServiceTest` |

边界差异：首版仅实现 Binance；其他交易所通过 `ExchangeGateway` 保留扩展点并返回明确“不支持”错误。WebSocket 使用 Binance 官方 Spot Streams，不再使用 CCXT。
