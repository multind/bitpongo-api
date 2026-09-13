package com.multind.bitpongo.notification;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativePushPolicyTest {
    @Test void publicLocaleAliasesAreCanonicalAndMissingConfigIsUnavailable() {
        assertThat(NativePushService.canonicalLocale("en-us")).isEqualTo("en-US");
        assertThat(NativePushService.canonicalLocale("zh-tw")).isEqualTo("zh-TW");
        assertThatThrownBy(()->NativePushService.validateLocaleZone("en-us","+08:00")).hasMessageContaining("IANA");
        assertThat(new FcmPushClient(true,"valid-project","").available()).isFalse();
        assertThat(new FcmPushClient(false,"valid-project","/missing").available()).isFalse();
    }
    @Test void failuresAreTimeSensitiveButSuccessIsNormalAndRecoveryQuiet() {
        assertThat(NativePushPolicy.urgency(NotificationEventType.TRADE_FAILED)).isEqualTo("time-sensitive");
        assertThat(NativePushPolicy.urgency(NotificationEventType.TRADE_SUCCEEDED)).isEqualTo("active");
        assertThat(NativePushPolicy.urgency(NotificationEventType.SYSTEM_RECOVERED)).isEqualTo("passive");
        assertThat(NativePushPolicy.channel(NotificationEventType.TRADE_FAILED, false)).isEqualTo("bitpongo_alerts_silent");
        assertThat(NativePushPolicy.channel(NotificationEventType.ORDER_MANUAL_REVIEW, true)).isEqualTo("bitpongo_alerts");
        assertThat(NativePushPolicy.channel(NotificationEventType.MARKET_OUTAGE, true)).isEqualTo("bitpongo_alerts");
        assertThat(NativePushPolicy.channel(NotificationEventType.TRADE_SUCCEEDED, true)).isEqualTo("bitpongo_trades");
    }
    @Test void genericInvalidPayloadDoesNotInvalidateToken() {
        assertThat(FcmPushClient.classify(400, "INVALID_ARGUMENT", 0).invalidToken()).isFalse();
        assertThat(FcmPushClient.classify(404, "UNREGISTERED", 0).invalidToken()).isTrue();
        assertThat(FcmPushClient.classify(429, "QUOTA_EXCEEDED", 0).retrySeconds()).isGreaterThanOrEqualTo(60);
        assertThat(FcmPushClient.classify(503, "UNAVAILABLE", 300).retrySeconds()).isEqualTo(300);
    }
    @Test void payloadUsesBoundedTextAndStringRoutingDataAndSilentChannels() {
        var payload=NativePushOutbox.message("event",7,"TRADE_FAILED",12L,"Title","body\u0000");
        assertThat((java.util.Map<?,?>)payload.get("data")).isEqualTo(java.util.Map.of("eventId","event","userId","7","eventType","TRADE_FAILED","planId","12"));
        assertThat((java.util.Map<?,?>)payload.get("notification")).isEqualTo(java.util.Map.of("title","Title","body","body"));
        var now=java.time.LocalDateTime.of(2026,9,13,0,0);
        NativePushOutbox.configureDelivery(payload,"TRADE_FAILED",false,now.plusSeconds(60),now);
        var android=(java.util.Map<?,?>)payload.get("android");
        assertThat(android.get("priority")).isEqualTo("high");
        assertThat(android.get("ttl")).isEqualTo("60s");
        var notification=(java.util.Map<?,?>)android.get("notification");
        assertThat(notification.get("channel_id")).isEqualTo("bitpongo_alerts_silent");
        assertThat(notification.containsKey("sound")).isFalse();
    }
}
