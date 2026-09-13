package com.multind.bitpongo.notification;

import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!test")
@Component
public final class NotificationAudienceResolver {

    private final PlanRepository plans;
    private final UserBarkSettingRepository settings;
    private final BarkProperties properties;

    @Autowired
    public NotificationAudienceResolver(
            PlanRepository plans,
            UserBarkSettingRepository settings,
            BarkProperties properties) {
        this.plans = plans;
        this.settings = settings;
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    NotificationAudienceResolver(PlanRepository plans, BarkProperties properties) {
        this(plans, null, properties);
    }

    public List<Audience> resolve(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        List<Audience> audiences = new ArrayList<>();
        switch (event.type()) {
            case SCHEDULER_FATAL -> {
                addUser(audiences, event.userId());
                addAdmin(audiences);
            }
            case ORDER_MANUAL_REVIEW -> {
                addUser(audiences, event.userId());
                addAdmin(audiences);
            }
            case TRADE_FAILED -> resolveTradeFailure(event.userId(), audiences);
            case MARKET_OUTAGE -> {
                if (event.audienceContext() == null) {
                    activePlanUserIds().forEach(userId -> addUser(audiences, userId));
                    addAdmin(audiences);
                } else {
                    resolveContext(event.audienceContext(), audiences);
                }
            }
            case PLAN_EXECUTION_SKIPPED -> {
                if (event.userId() == null) addAdmin(audiences);
                else addUser(audiences, event.userId());
            }
            case PLAN_EXECUTION_DELAYED, TRADE_SUCCEEDED, ASSET_SNAPSHOT_FAILED ->
                    addUser(audiences, event.userId());
            case SYSTEM_RECOVERED -> resolveContext(event.audienceContext(), audiences);
            case SERVICE_STARTED -> addAdmin(audiences);
            case BARK_TEST -> {
                // Direct Bark tests are sent by UserBarkSettingService, never via outbox.
            }
        }
        return List.copyOf(audiences);
    }

    public NotificationAudienceContext snapshotMarketOutageAudience() {
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        userIds.addAll(activePlanUserIds());
        boolean admin = properties.adminPushUrl() != null
                && !properties.adminPushUrl().isBlank();
        return new NotificationAudienceContext(userIds, admin);
    }

    public java.util.Set<Long> resolveNative(NotificationEvent event) {
        return switch (event.type()) {
            case MARKET_OUTAGE -> event.audienceContext() == null ? activePlanUserIds() : event.audienceContext().recipientUserIds();
            case SYSTEM_RECOVERED -> event.audienceContext() == null ? java.util.Set.of() : event.audienceContext().recipientUserIds();
            case SERVICE_STARTED -> activePlanUserIds();
            case BARK_TEST -> java.util.Set.of();
            default -> event.userId() == null ? java.util.Set.of() : java.util.Set.of(event.userId());
        };
    }

    private void resolveTradeFailure(Long userId, List<Audience> audiences) {
        boolean enabledUserTarget = properties.userNotificationsEnabled()
                && userId != null
                && settings != null
                && settings.findByUserId(userId)
                        .filter(UserBarkSettingEntity::isEnabled)
                        .isPresent();
        if (enabledUserTarget) {
            audiences.add(new Audience(NotificationRecipientType.USER, userId));
        } else {
            addAdmin(audiences);
        }
    }

    private void resolveContext(
            NotificationAudienceContext context,
            List<Audience> audiences) {
        if (context == null) return;
        context.recipientUserIds().forEach(userId -> addUser(audiences, userId));
        if (context.admin()) addAdmin(audiences);
    }

    private LinkedHashSet<Long> activePlanUserIds() {
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        if (plans != null) {
            plans.findByStatus("active").stream()
                    .map(PlanEntity::getUserId)
                    .filter(Objects::nonNull)
                    .forEach(userIds::add);
        }
        return userIds;
    }

    private void addUser(List<Audience> audiences, Long userId) {
        if (properties.userNotificationsEnabled() && userId != null) {
            audiences.add(new Audience(NotificationRecipientType.USER, userId));
        }
    }

    private void addAdmin(List<Audience> audiences) {
        if (properties.adminPushUrl() != null && !properties.adminPushUrl().isBlank()) {
            audiences.add(new Audience(NotificationRecipientType.ADMIN, null));
        }
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
