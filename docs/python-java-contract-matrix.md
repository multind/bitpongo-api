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
| 用户时区 | `GET /api/users/timezone` | `UserController.timezone` | `UserControllerContractTest` |
| 用户时区 | `PUT /api/users/timezone` | `UserController.updateTimezone` | `UserControllerContractTest` |
| 用户时区 | `POST /api/users/timezone/device` | `UserController.syncDeviceTimezone` | `UserControllerContractTest` |
| 用户通知 | Bark 设置 `GET /api/users/notifications/bark` | `NotificationController.getBarkSetting` | `NotificationControllerContractTest` |
| 用户通知 | Bark 设置 `PUT /api/users/notifications/bark` | `NotificationController.updateBarkSetting` | `NotificationControllerContractTest` |
| 用户通知 | Bark 设置 `DELETE /api/users/notifications/bark` | `NotificationController.deleteBarkSetting` | `NotificationControllerContractTest` |
| 用户通知 | Bark 测试 `POST /api/users/notifications/bark/test` | `NotificationController.testBark` | `NotificationControllerContractTest` |
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

## 时间与时区兼容约定

- `created_at`、`updated_at`、`next_time`、订单时间和通知 outbox 时间均为绝对时间，Java API 使用带 `Z` 的 UTC ISO-8601 字符串返回。
- 策略创建请求新增 `schedule_timezone`。兼容期内缺省值仍可由后端填充，但新前端必须显式发送 IANA 时区名。
- 用户显示偏好通过 `/api/users/timezone` 管理；`FOLLOW_DEVICE` 由移动端或浏览器通过 `/device` 同步设备 IANA 时区，`FIXED` 保存用户选择。
- `schedule_timezone` 只决定执行语义；用户显示时区只决定 UI 与 Bark 文案转换，二者不得互相覆盖。
- 交易通知同时包含 `scheduledAt` 和 `occurredAt`，以区分 Quartz 计划火次与真实成交/失败发生时间。
- 旧客户端在兼容期内仍可读取既有 snake_case 字段；删除别名或收紧必填项必须安排在后续版本。
