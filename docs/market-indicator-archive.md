# Fear & Greed history archive

Flyway V23 stores validated Alternative.me Fear & Greed observations in MySQL. Observation UTC time is the key; repeated fetches update corrections instead of inserting duplicates. Earlier observations remain available across restarts. The existing public history endpoint still accepts 1–365 points; this batch does not add other index providers or a multi-year paging API.

If the provider is unavailable after restart, archived data is returned with `stale=true` and the saved fetch time. `latestFresh()` rejects stale data, so archive fallback never triggers new strategy signals. With no cache/archive, provider errors remain explicit; a database failure does not manufacture a zero index value or mark old data fresh. Normal requests retain the five-minute fetch cache and a one-minute retry interval after failure.

No extra credentials or volumes are required. Deploy V23 with this API version. The archive is populated by actual successful fetches; old tradebot index records have not been imported. Alternative.me attribution remains in the API response and UI.

## 中文说明

V23 将经过校验的恐惧贪婪指数保存到 MySQL，以 UTC 观测时间去重并更新修订。重启后上游不可用时读取历史，但始终标记过期，不产生新的策略信号。无历史或数据库故障时不会用 0 值假装正常。当前接口仍最多查询 365 条；其他指标提供器、多年分页和旧数据导入不属于本批交付。
