package com.multind.bitpongo.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!test")
@Service
public final class OutboxNotificationPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxNotificationPublisher.class);

    private final NotificationOutboxEnqueuer enqueuer;

    public OutboxNotificationPublisher(NotificationOutboxEnqueuer enqueuer) {
        this.enqueuer = enqueuer;
    }

    @Override
    public void publish(NotificationEvent event) {
        try {
            enqueuer.enqueue(event);
        } catch (Exception exception) {
            log.warn("通知入队失败 eventType={} errorType={}",
                    event == null || event.type() == null ? "UNKNOWN" : event.type(),
                    exception.getClass().getSimpleName());
        }
    }
}
