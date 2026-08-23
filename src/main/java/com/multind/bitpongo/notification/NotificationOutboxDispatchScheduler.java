package com.multind.bitpongo.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!test")
@Component
@ConditionalOnProperty(
        prefix = "zhitoubao.notifications.bark",
        name = "dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true)
final class NotificationOutboxDispatchScheduler {

    private final NotificationOutboxDispatcher dispatcher;

    NotificationOutboxDispatchScheduler(NotificationOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            fixedDelayString = "${zhitoubao.notifications.bark.dispatch-delay:5s}",
            initialDelayString = "${zhitoubao.notifications.bark.dispatch-initial-delay:5s}")
    void dispatchDue() {
        dispatcher.dispatchDue();
    }
}
