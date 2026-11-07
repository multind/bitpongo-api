# Notification perception escalation

When confirmation escalation is enabled, send the first eligible strength tier immediately. Wait five minutes from dispatching the tier, then send the next eligible tier only if the user has not explicitly confirmed in the app. Channels in the same tier are dispatched together; empty tiers do not add waiting time. Do not repeat a tier after escalation or loop after the strongest tier. Provider-specific delivery retries are separate from escalation.

- Supported channels are App, email, Telegram and Webhook. Treat Webhook as one channel with separately enabled targets; SMS and phone stay hidden.
- Eligibility requires a configured, available and enabled channel. For price alerts, intersect with the alert's selected channels. An explicit empty selection sends nothing; legacy rules must not gain Webhook implicitly.
- Store each event's chosen tiers durably and deduplicate channel dispatch per event. A stage must recheck current channel eligibility and confirmation before dispatch. Do not add channels that were disabled or unselected when planning the alert.
- Keep the in-app confirmation record separate from read state. Confirming, deleting the inbox item or turning off escalation cancels undispatched follow-up stages.
- When confirmation escalation is off, eligible channels for the reminder are dispatched immediately, without follow-up. Keep security, system and administrator alerts outside this policy.

Implementation steps:

1. Group eligible channels by strength, independent of visual drag order.
2. Implement durable scheduling and hook inbox-backed alert events into it. Route each stage through the existing channel adapters without also using their direct-send path. Preserve legacy pending jobs during migration.
3. Expose strengths and confirmation configuration in notification settings, including Webhook. Cover explicit opt-in, same-tier delivery, timing, confirmation, failure isolation and queue recovery before publishing.
