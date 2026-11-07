package com.multind.bitpongo.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing channels commit first. Webhook fanout failures cannot undo their writes. */
@Component
@Profile("!test")
class WebhookReminderEventListener {
    private static final Logger log = LoggerFactory.getLogger(WebhookReminderEventListener.class);
    private final WebhookReminderOutbox outbox;
    private final TransactionTemplate tx;

    record Candidate(NotificationEvent event, boolean priceOptIn) {}

    WebhookReminderEventListener(WebhookReminderOutbox outbox, PlatformTransactionManager manager) {
        this.outbox = outbox;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(fallbackExecution = true)
    void afterCommit(Candidate candidate) {
        if (!outbox.available()) return;
        try {
            tx.executeWithoutResult(ignored -> {
                if (candidate.priceOptIn()) outbox.enqueuePrice(candidate.event());
                else outbox.enqueue(candidate.event());
            });
        } catch (RuntimeException failure) {
            log.warn("Webhook fanout failed for event type {} ({})",
                    candidate.event().type(), failure.getClass().getSimpleName());
        }
    }
}
