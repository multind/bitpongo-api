package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAudienceResolverContextTest {

    @Test
    void recoverySnapshotIsIntersectedWithCurrentGlobalUserAndAdminConfiguration() {
        NotificationAudienceContext snapshot =
                new NotificationAudienceContext(Set.of(41L), true);

        NotificationAudienceResolver usersDisabled = new NotificationAudienceResolver(
                null, properties(false, "https://localhost/admin-device"));
        assertThat(usersDisabled.resolve(recovered(snapshot)))
                .containsExactly(new NotificationAudienceResolver.Audience(
                        NotificationRecipientType.ADMIN, null));

        NotificationAudienceResolver adminDisabled = new NotificationAudienceResolver(
                null, properties(true, ""));
        assertThat(adminDisabled.resolve(recovered(snapshot)))
                .containsExactly(new NotificationAudienceResolver.Audience(
                        NotificationRecipientType.USER, 41L));
    }

    @Test
    void recoverySkipWithoutAResolvedOwnerFallsBackToConfiguredAdmin() {
        NotificationAudienceResolver resolver = new NotificationAudienceResolver(
                null, properties(true, "https://localhost/admin-device"));
        NotificationEvent skipped = new NotificationEvent(
                NotificationEventType.PLAN_EXECUTION_SKIPPED,
                null,
                42L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                "plan-execution-skipped:recovery:42",
                Map.of("status", "RECOVERY_SKIPPED"));

        assertThat(resolver.resolve(skipped))
                .containsExactly(new NotificationAudienceResolver.Audience(
                        NotificationRecipientType.ADMIN, null));
    }

    private static NotificationEvent recovered(NotificationAudienceContext context) {
        return new NotificationEvent(
                NotificationEventType.SYSTEM_RECOVERED,
                null,
                null,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                "system-recovered:config-intersection",
                Map.of("status", "RECOVERED", "originalEventType", "MARKET_OUTAGE"),
                context);
    }

    private static BarkProperties properties(boolean users, String adminUrl) {
        return new BarkProperties(
                users,
                adminUrl,
                Set.of("localhost"),
                true,
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                false,
                false,
                "https://app.example.com");
    }
}
