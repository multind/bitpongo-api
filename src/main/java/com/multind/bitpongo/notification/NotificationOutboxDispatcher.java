package com.multind.bitpongo.notification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!test")
@Service
public class NotificationOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxDispatcher.class);
    private static final String GENERIC_SEND_ERROR = "Bark 通知发送失败";

    private final NotificationOutboxLeaseService leases;
    private final NotificationOutboxDeliveryStore deliveries;
    private final BarkNotificationSender sender;
    private final NotificationMessageRenderer renderer;
    private final NotificationRetryPolicy retries;
    private final BarkProperties properties;
    private final BarkPushUrlParser parser;
    private final Clock clock;

    public NotificationOutboxDispatcher(
            NotificationOutboxLeaseService leases,
            NotificationOutboxDeliveryStore deliveries,
            BarkNotificationSender sender,
            NotificationMessageRenderer renderer,
            NotificationRetryPolicy retries,
            BarkProperties properties,
            ObjectProvider<Clock> clock) {
        this.leases = leases;
        this.deliveries = deliveries;
        this.sender = sender;
        this.renderer = renderer;
        this.retries = retries;
        this.properties = properties;
        this.parser = new BarkPushUrlParser(properties);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public void dispatchDue() {
        final List<NotificationOutboxLeaseService.Lease> claimed;
        try {
            claimed = leases.leaseDue(now());
        } catch (Exception exception) {
            log.warn("Bark outbox 领取失败 errorType={}",
                    exception.getClass().getSimpleName());
            return;
        }
        claimed.forEach(this::dispatchOne);
    }

    void dispatchOne(NotificationOutboxLeaseService.Lease lease) {
        NotificationOutboxDeliveryStore.LeasedNotification delivery = null;
        try {
            LocalDateTime renewalTime = now();
            if (!leases.renew(lease, renewalTime)) {
                return;
            }
            Optional<NotificationOutboxDeliveryStore.LeasedNotification> loaded =
                    deliveries.load(lease);
            if (loaded.isEmpty()) {
                return;
            }
            delivery = loaded.orElseThrow();
            BarkTarget target = target(delivery);
            if (target == null) {
                deliveries.markSkipped(lease, now());
                return;
            }
            BarkMessage message = renderer.render(
                    delivery.event(),
                    delivery.locale(),
                    delivery.timezone(),
                    blankToNull(properties.appPublicUrl()));
            sender.send(target, message);
            deliveries.markSent(lease, now());
        } catch (Exception exception) {
            LocalDateTime completionTime = now();
            int previousAttempts = delivery == null
                    ? deliveries.currentAttempts(lease).orElse(0)
                    : delivery.attempts();
            markFailure(lease, previousAttempts + 1, completionTime, exception);
        }
    }

    private BarkTarget target(
            NotificationOutboxDeliveryStore.LeasedNotification delivery) {
        if (delivery.recipientType() == NotificationRecipientType.ADMIN) {
            return blankToNull(properties.adminPushUrl()) == null
                    ? null
                    : parser.parse(properties.adminPushUrl());
        }
        if (!delivery.hasStoredUserTarget()) {
            return null;
        }
        String key = new BarkCredentialCipher(properties)
                .decrypt(delivery.deviceKeyCiphertext());
        return parser.parse(stripTrailingSlash(delivery.serverUrl()) + "/" + encode(key));
    }

    private void markFailure(
            NotificationOutboxLeaseService.Lease lease,
            int attempts,
            LocalDateTime now,
            Exception exception) {
        String lastError = sanitizedError(exception);
        try {
            if (retries.isDead(attempts)) {
                deliveries.markDead(lease, attempts, lastError, now);
            } else {
                deliveries.markRetry(
                        lease,
                        attempts,
                        retries.nextAttemptAt(now, attempts),
                        lastError,
                        now);
            }
        } catch (Exception persistenceFailure) {
            log.warn("Bark outbox 状态更新失败 messageId={} errorType={}",
                    lease.id(), persistenceFailure.getClass().getSimpleName());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String sanitizedError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return GENERIC_SEND_ERROR;
        }
        String sanitized = NotificationMessageRenderer.sanitizeError(message);
        return sanitized.isBlank() ? GENERIC_SEND_ERROR : sanitized;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String value) {
        String stripped = value;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
