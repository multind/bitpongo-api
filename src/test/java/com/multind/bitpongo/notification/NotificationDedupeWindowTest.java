package com.multind.bitpongo.notification;

import java.time.Duration;
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
}
