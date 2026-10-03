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
    public AlertNotificationBridge(NotificationOutboxEnqueuer enqueuer) { this.enqueuer = enqueuer; }
    @Transactional public void priceAlert(long userId, String eventKey, String symbol, String summary, Instant at) {
        enqueuer.enqueueInCurrentTransaction(new NotificationEvent(NotificationEventType.PRICE_ALERT_TRIGGERED, userId,
                null, null, at, eventKey, Map.of("symbol", symbol, "status", summary)));
    }
    @Transactional public void webhook(long userId,String eventKey,String summary,Instant at) {
        enqueuer.enqueueInCurrentTransaction(new NotificationEvent(NotificationEventType.WEBHOOK_TRIGGERED,userId,null,null,at,eventKey,Map.of("status",summary)));
    }
}
