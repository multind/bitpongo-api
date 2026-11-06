# Strategy signals and Fear & Greed

Strategy alerts are separate from exchange order plans. DCA links to the existing plan-creation workflow; subscribing to the Fear & Greed Index never creates a plan or places an order.

## Data and deployment

- Source: [Alternative.me](https://alternative.me/crypto/fear-and-greed-index/). Attribution is displayed beside the index, as required by the provider.
- Default provider URL: `https://api.alternative.me`. Optional server-only override: `FEAR_GREED_API_BASE_URL`. It is never taken from a user's request.
- Allow outbound HTTPS to the provider. The HTTP client has a five-second connection timeout, ten-second request timeout and bounded response body.
- The API returns up to 365 provider observations, with their original UTC timestamps. The in-process cache lasts five minutes; there is no local synthetic history or disk-backed archive.
- Refresh failures retain cached values for display with `stale=true`; cold-start failures return an explicit error. Values older than 48 hours are stale even after a successful fetch. Stale values cannot generate signals.
- Signal polling defaults to ten minutes (`STRATEGY_SIGNAL_POLL_DELAY`, `STRATEGY_SIGNAL_INITIAL_DELAY` in milliseconds or Spring duration syntax).

## Subscription semantics

Authenticated users save their own settings at `PUT /api/strategy-subscriptions/fear-greed`: `enabled`, `fearThreshold` and `greedThreshold`. Thresholds are integers between 0 and 100, with fear strictly below greed. `GET` returns the current settings or null; `DELETE` removes them.

Only observations published after enabling or changing settings are processed. No historical alert is sent immediately on subscription. Values at or below the fear threshold, or at or above the greed threshold, notify once per new observation. Neutral observations advance the sampling cursor without notification. Saving unchanged settings does not reset the cursor.

Processing locks the user and subscription, records the event and enqueues notification delivery in one transaction. The event identity prevents duplicate delivery when the same observation is sampled again. Existing plan-alert notification preferences control delivery. Account deletion removes the subscription and its event data.

The index describes Bitcoin sentiment, not a buy/sell recommendation. It does not represent sentiment for every cryptocurrency.

## F&G futures execution

Futures strategies are separate from signal-only subscriptions. An unresolved entry keeps its original client order ID and is reconciled without resubmission. Open positions are checked in oldest-checked order, including when an individual lookup fails.

The maximum trade rounds setting counts fully confirmed take-profit, stop-loss, and trailing-stop exits once per position, not holding days. Reaching the limit stops new entries without abandoning existing positions. Existing positions can finish and raise the count above the limit. Raising the limit does not reset the count or automatically resume the strategy.

V46 preserves legacy day settings only as historical data and pauses those strategies for new entries. Existing positions continue to use the risk rules. An old timed exit already submitted is reconciled, but an unfilled remainder needs review instead of placing another timed exit order. Owners must set a round limit and resume explicitly. Historical exits are not backfilled; qualifying exits confirmed from V46 onward count toward the limit.

Stop all pre-V46 F&G workers before applying V46 so they cannot run the old time-based exit rule. The entry claim (`PENDING_ENTRY` to `OPENING`) is the durable in-flight boundary: once the cap is reached, no further claims are allowed, but an already claimed order can still complete.

Deleting a strategy with unresolved orders or exposure returns a conflict. A strategy with completed trades is archived rather than erased; it no longer runs or appears in the public square. Owners can list archives at `GET /api/fng-strategies/archive?page=0&size=10` and read their history through the existing detail, positions and executions endpoints. Strategies without fills or unresolved orders can be deleted.

Before enabling live execution, reconcile any legacy position history against the exchange. A legacy `CLOSED` status alone does not establish that the old entry and exit quantities matched.

## Mobile compatibility

Native shells must accept `PRICE_ALERT_TRIGGERED`, `WEBHOOK_TRIGGERED` and `STRATEGY_SIGNAL` in their notification-event allowlist. The H5 bridge routes those notifications to `/alerts/events` after checking the logged-in account. The current mobile checkout has not yet been changed in this migration; its old allowlist rejects these events, so native notification click-through is not verified or release-ready.
