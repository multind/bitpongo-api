package com.multind.bitpongo.notification;

import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!test")
@Component
public final class NotificationAudienceResolver {

    private final PlanRepository plans;
    private final BarkProperties properties;

    public NotificationAudienceResolver(PlanRepository plans, BarkProperties properties) {
        this.plans = plans;
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public List<Audience> resolve(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        List<Audience> audiences = new ArrayList<>();
        if (properties.userNotificationsEnabled()) {
            userIds(event).forEach(userId -> audiences.add(
                    new Audience(NotificationRecipientType.USER, userId)));
        }
        if (properties.adminPushUrl() != null && !properties.adminPushUrl().isBlank()) {
            audiences.add(new Audience(NotificationRecipientType.ADMIN, null));
        }
        return List.copyOf(audiences);
    }

    private LinkedHashSet<Long> userIds(NotificationEvent event) {
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        if (event.type() == NotificationEventType.MARKET_OUTAGE) {
            if (plans != null) {
                plans.findByStatus("active").stream()
                        .map(PlanEntity::getUserId)
                        .filter(Objects::nonNull)
                        .forEach(userIds::add);
            }
        } else if (event.userId() != null) {
            userIds.add(event.userId());
        }
        return userIds;
    }

    public record Audience(NotificationRecipientType recipientType, Long userId) {

        public Audience {
            Objects.requireNonNull(recipientType, "recipientType");
            if (recipientType == NotificationRecipientType.USER) {
                Objects.requireNonNull(userId, "userId");
            } else if (userId != null) {
                throw new IllegalArgumentException("ADMIN audience must not carry a user id");
            }
        }
    }
}
