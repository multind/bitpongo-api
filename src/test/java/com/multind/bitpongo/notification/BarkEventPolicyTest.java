package com.multind.bitpongo.notification;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class BarkEventPolicyTest {

    private final BarkEventPolicy policies = new BarkEventPolicy();

    @ParameterizedTest(name = "{0} -> {1}, call={3}, sound={4}, group={5}")
    @MethodSource("policyMatrix")
    void mapsEveryEventToItsApprovedPolicy(
            NotificationEventType type,
            String level,
            Integer volume,
            boolean call,
            String sound,
            String group) {
        BarkEventPolicy.Policy actual = policies.policy(type);

        assertThat(actual.level()).isEqualTo(level);
        assertThat(actual.volume()).isEqualTo(volume);
        assertThat(actual.call()).isEqualTo(call);
        assertThat(actual.sound()).isEqualTo(sound);
        assertThat(actual.group()).isEqualTo(group);
    }

    private static Stream<Arguments> policyMatrix() {
        return Stream.of(
                Arguments.of(NotificationEventType.SCHEDULER_FATAL,
                        "critical", 10, true, "alarm", "Bitpongo·紧急"),
                Arguments.of(NotificationEventType.ORDER_MANUAL_REVIEW,
                        "critical", 10, true, "alarm", "Bitpongo·紧急"),
                Arguments.of(NotificationEventType.TRADE_FAILED,
                        "timeSensitive", null, false, "alarm", "Bitpongo·交易异常"),
                Arguments.of(NotificationEventType.MARKET_OUTAGE,
                        "timeSensitive", null, false, "alarm", "Bitpongo·行情"),
                Arguments.of(NotificationEventType.PLAN_EXECUTION_SKIPPED,
                        "timeSensitive", null, false, null, "Bitpongo·计划"),
                Arguments.of(NotificationEventType.PLAN_EXECUTION_DELAYED,
                        "timeSensitive", null, false, null, "Bitpongo·计划"),
                Arguments.of(NotificationEventType.TRADE_SUCCEEDED,
                        "active", null, false, "minuet", "Bitpongo·交易"),
                Arguments.of(NotificationEventType.ASSET_SNAPSHOT_FAILED,
                        "active", null, false, null, "Bitpongo·资产"),
                Arguments.of(NotificationEventType.SYSTEM_RECOVERED,
                        "passive", null, false, null, null),
                Arguments.of(NotificationEventType.SERVICE_STARTED,
                        "passive", null, false, null, "Bitpongo·系统"),
                Arguments.of(NotificationEventType.BARK_TEST,
                        "active", null, false, "minuet", "Bitpongo·测试"));
    }
}
