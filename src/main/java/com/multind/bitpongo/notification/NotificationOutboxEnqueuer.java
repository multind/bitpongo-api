package com.multind.bitpongo.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!test")
@Service
class NotificationOutboxEnqueuer {

    private static final int MAX_DEDUPE_KEY_LENGTH = 191;

    private final NotificationOutboxRepository outbox;
    private final NotificationAudienceResolver audiences;
    private final BarkEventPolicy policies;
    private final Clock clock;

    @Autowired
    NotificationOutboxEnqueuer(
            NotificationOutboxRepository outbox,
            NotificationAudienceResolver audiences,
            BarkEventPolicy policies) {
        this(outbox, audiences, policies, Clock.systemUTC());
    }

    NotificationOutboxEnqueuer(
            NotificationOutboxRepository outbox,
            NotificationAudienceResolver audiences,
            BarkEventPolicy policies,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.audiences = Objects.requireNonNull(audiences, "audiences");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        String baseDedupeKey = Objects.requireNonNull(event.dedupeKey(), "dedupeKey");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (NotificationAudienceResolver.Audience audience : audiences.resolve(event)) {
            String dedupeKey = recipientDedupeKey(baseDedupeKey, audience);
            if (outbox.existsByDedupeKey(dedupeKey)) {
                continue;
            }
            outbox.saveAndFlush(message(event, audience, dedupeKey, now));
        }
    }

    private NotificationOutboxEntity message(
            NotificationEvent event,
            NotificationAudienceResolver.Audience audience,
            String dedupeKey,
            LocalDateTime now) {
        NotificationOutboxEntity message = new NotificationOutboxEntity();
        message.setEventType(event.type());
        message.setRecipientType(audience.recipientType());
        message.setUserId(audience.userId());
        message.setTitleKey("notification."
                + event.type().name().toLowerCase(Locale.ROOT) + ".title");
        message.setBodyPayload(payload(event));
        message.setDedupeKey(dedupeKey);
        message.setPriority(priority(policies.policy(event).level()));
        message.setStatus(NotificationOutboxStatus.PENDING);
        message.setAttempts(0);
        message.setNextAttemptAt(now);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
    }

    private static Map<String, Object> payload(NotificationEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotNull(payload, "userId", event.userId());
        putIfNotNull(payload, "planId", event.planId());
        putIfNotNull(payload, "intentId", event.intentId());
        putIfNotNull(payload, "occurredAt",
                event.occurredAt() == null ? null : event.occurredAt().toString());
        payload.put("attributes", event.attributes() == null ? Map.of() : event.attributes());
        return payload;
    }

    private static void putIfNotNull(Map<String, Object> payload, String name, Object value) {
        if (value != null) {
            payload.put(name, value);
        }
    }

    private static String priority(String level) {
        return switch (level) {
            case "critical" -> "00_CRITICAL";
            case "timeSensitive" -> "10_TIME_SENSITIVE";
            case "active" -> "20_ACTIVE";
            default -> "30_PASSIVE";
        };
    }

    private static String recipientDedupeKey(
            String base, NotificationAudienceResolver.Audience audience) {
        String suffix = audience.recipientType() == NotificationRecipientType.USER
                ? ":USER:" + audience.userId()
                : ":ADMIN";
        String value = base + suffix;
        if (value.length() <= MAX_DEDUPE_KEY_LENGTH) {
            return value;
        }
        String hash = sha256(value);
        int prefixLength = MAX_DEDUPE_KEY_LENGTH - hash.length() - 1;
        return value.substring(0, prefixLength) + ":" + hash;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
