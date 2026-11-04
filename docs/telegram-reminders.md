# Telegram price reminders

Create a dedicated private-chat bot with BotFather. Configure the API container
with `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` (without @), and
`TELEGRAM_WEBHOOK_SECRET` (32–256 ASCII letters, digits, underscores or hyphens).
Store secrets in the deployment environment and do not put them in source control.

After deploying migration V40, use Telegram Bot API `setWebhook` for
`https://<app-host>/api/integrations/telegram/webhook`, with
`secret_token` matching the server configuration and `allowed_updates` set to
`["message"]`. The server must be able to reach `api.telegram.org` over HTTPS.

In Notification Methods, open Telegram settings, generate the link, and tap
Start in the bot's private chat. Return to the settings, refresh, and send a
test message. Enable Telegram and save notification preferences. Select
Telegram when creating a price alert. Alerts without a per-alert selection use
account defaults; an explicit selection is respected.

The initial implementation sends once after a committed price trigger.
Telegram outages can cause missed Telegram messages; App and email flows
are unchanged. Telegram group chats are not supported.
