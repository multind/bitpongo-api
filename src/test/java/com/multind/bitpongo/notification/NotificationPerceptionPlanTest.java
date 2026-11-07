package com.multind.bitpongo.notification;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static com.multind.bitpongo.notification.NotificationPerceptionPlan.*;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationPerceptionPlanTest {
    private static final List<Channel> CHANNELS = List.of(
            new Channel("app", Strength.STRONG, true),
            new Channel("telegram", Strength.MODERATE, true),
            new Channel("email", Strength.WEAK, true),
            new Channel("webhook", Strength.MODERATE, true),
            new Channel("phone", Strength.STRONG, false));

    @Test void skipsEmptyStrengthsAndGroupsTiedChannels() {
        assertThat(stages(CHANNELS, null, true)).containsExactly(
                List.of("email"), List.of("telegram", "webhook"), List.of("app"));
    }

    @Test void usesOnlyExplicitlySelectedAndEnabledPriceAlertChannels() {
        assertThat(stages(CHANNELS, Set.of("email", "phone", "webhook"), true))
                .containsExactly(List.of("email"), List.of("webhook"));
        assertThat(stages(CHANNELS, Set.of(), true)).isEmpty();
    }

    @Test void withoutConfirmationAllSelectedChannelsAreInTheImmediateStage() {
        assertThat(stages(CHANNELS, Set.of("email", "webhook"), false)).containsExactly(List.of("email", "webhook"));
    }
}
