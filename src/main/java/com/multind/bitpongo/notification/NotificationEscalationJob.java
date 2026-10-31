package com.multind.bitpongo.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
final class NotificationEscalationJob {
    private static final Logger log = LoggerFactory.getLogger(NotificationEscalationJob.class);

    private final NotificationEscalationService escalations;

    NotificationEscalationJob(NotificationEscalationService escalations) {
        this.escalations = escalations;
    }

    @Scheduled(
            fixedDelayString = "${NOTIFICATION_ESCALATION_DELAY:30s}",
            initialDelayString = "${NOTIFICATION_ESCALATION_INITIAL_DELAY:30s}")
    void run() {
        try {
            escalations.escalateDue();
        } catch (RuntimeException exception) {
            log.warn("通知确认升级任务失败 errorType={}", exception.getClass().getSimpleName());
        }
    }
}
