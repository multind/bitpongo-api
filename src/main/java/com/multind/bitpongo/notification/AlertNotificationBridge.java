package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert trigger and notification fanout commit in the same database transaction. */
@Service @Profile("!test")
public class AlertNotificationBridge {
    private final NotificationOutboxEnqueuer enqueuer;
    private final NotificationEscalationService escalations;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final WebhookReminderOutbox outboundWebhook;
    public AlertNotificationBridge(NotificationOutboxEnqueuer enqueuer, NotificationEscalationService escalations,
            org.springframework.context.ApplicationEventPublisher events, WebhookReminderOutbox outboundWebhook) {
        this.enqueuer = enqueuer;
        this.escalations = escalations;
        this.events = events;
        this.outboundWebhook = outboundWebhook;
    }
    @Transactional public void priceAlert(long userId, String eventKey, String symbol, String summary, Instant at) {
        priceAlert(userId, eventKey, symbol, summary, at, null);
    }
    @Transactional public void priceAlert(long userId, String eventKey, String symbol, String summary, Instant at, java.util.List<String> channels) {
        if (channels == null || channels.contains("telegram"))
            events.publishEvent(new TelegramReminderService.Delivery(userId, "Bitpongo · " + symbol + "\n" + summary));
        if (channels == null || channels.contains("email")) escalations.schedule(userId, eventKey, at);
        var event = new NotificationEvent(NotificationEventType.PRICE_ALERT_TRIGGERED, userId,
                null, null, at, eventKey, Map.of("symbol", symbol, "status", summary));
        if (channels == null || channels.contains("bitpongo")) enqueuer.enqueueInCurrentTransaction(event);
        if (channels != null && channels.contains("webhook")) outboundWebhook.enqueuePrice(event);
    }
    @Transactional public void webhook(long userId,String eventKey,String summary,Instant at) {
        escalations.schedule(userId, eventKey, at);
        enqueuer.enqueueInCurrentTransaction(new NotificationEvent(NotificationEventType.WEBHOOK_TRIGGERED,userId,null,null,at,eventKey,Map.of("status",summary)));
    }
    @Transactional public void strategy(long userId,String eventKey,String summary,Instant at) {
        escalations.schedule(userId, eventKey, at);
        enqueuer.enqueueInCurrentTransaction(new NotificationEvent(NotificationEventType.STRATEGY_SIGNAL,userId,null,null,at,eventKey,Map.of("status",summary)));
    }
}
