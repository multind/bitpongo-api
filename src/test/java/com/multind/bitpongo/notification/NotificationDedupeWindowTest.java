package com.multind.bitpongo.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NotificationDedupeWindowTest {

    @Test
    void acceptsAStableBoundedScopeAndPositiveDuration() {
        NotificationDedupeWindow window = new NotificationDedupeWindow(
                "scheduler-fatal:plan-purchase:42", Duration.ofMinutes(10));

        assertThat(window.scopeKey()).isEqualTo("scheduler-fatal:plan-purchase:42");
        assertThat(window.duration()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsBlankOrOversizedScopes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NotificationDedupeWindow(" ", Duration.ofMinutes(10)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NotificationDedupeWindow(
                        "x".repeat(NotificationDedupeWindow.MAX_SCOPE_KEY_LENGTH + 1),
                        Duration.ofMinutes(10)));
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NotificationDedupeWindow(
                        "scheduler-fatal:plan-purchase:42", Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NotificationDedupeWindow(
                        "scheduler-fatal:plan-purchase:42", Duration.ofSeconds(-1)));
    }

    @Test
    void eventAcceptsOnlyTheWindowContractForItsType() {
        NotificationEvent scheduler = event(
                NotificationEventType.SCHEDULER_FATAL,
                new NotificationDedupeWindow(
                        "scheduler-fatal:plan-purchase:42", Duration.ofMinutes(10)));
        NotificationEvent snapshot = event(
                NotificationEventType.ASSET_SNAPSHOT_FAILED,
                new NotificationDedupeWindow(
                        "asset-snapshot-failed:42", Duration.ofMinutes(30)));

        assertThat(scheduler.dedupeWindow().duration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(snapshot.dedupeWindow().duration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void eventRejectsWrongScopeOrDurationForWindowedTypes() {
        assertThatIllegalArgumentException().isThrownBy(() -> event(
                NotificationEventType.SCHEDULER_FATAL,
                new NotificationDedupeWindow(
                        "asset-snapshot-failed:42", Duration.ofMinutes(10))));
        assertThatIllegalArgumentException().isThrownBy(() -> event(
                NotificationEventType.SCHEDULER_FATAL,
                new NotificationDedupeWindow(
                        "scheduler-fatal:plan-purchase:42", Duration.ofMinutes(30))));
        assertThatIllegalArgumentException().isThrownBy(() -> event(
                NotificationEventType.ASSET_SNAPSHOT_FAILED,
                new NotificationDedupeWindow(
                        "scheduler-fatal:asset-snapshot:42", Duration.ofMinutes(30))));
        assertThatIllegalArgumentException().isThrownBy(() -> event(
                NotificationEventType.ASSET_SNAPSHOT_FAILED,
                new NotificationDedupeWindow(
                        "asset-snapshot-failed:42", Duration.ofMinutes(10))));
    }

    @Test
    void eventRejectsWindowsForOtherEventTypes() {
        assertThatIllegalArgumentException().isThrownBy(() -> event(
                NotificationEventType.ORDER_MANUAL_REVIEW,
                new NotificationDedupeWindow(
                        "scheduler-fatal:order:42", Duration.ofMinutes(10))));
    }

    private static NotificationEvent event(
            NotificationEventType type, NotificationDedupeWindow window) {
        return new NotificationEvent(
                type, 7L, 42L, null,
                Instant.parse("2026-08-24T00:00:00Z"),
                "stable-dedupe-key", Map.of(), null, window);
    }
}
