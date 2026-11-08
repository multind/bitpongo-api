package com.multind.bitpongo.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationLocaleCopyTest {
    @Test
    void supportsEveryNewLocaleForAllNotificationEventsAndTestCopy() {
        for (String locale : List.of("es-MX", "pt-BR", "ja", "ko", "id", "th", "vi", "ar", "fr", "de", "it", "es-ES")) {
            assertThat(NativePushService.canonicalLocale(locale.toLowerCase(java.util.Locale.ROOT))).isEqualTo(locale);
            var copy = NotificationLocaleCopy.find(locale);
            assertThat(copy).isNotNull();
            for (var event : NotificationEventType.values()) {
                assertThat(copy.title(event)).isNotBlank();
            }
            assertThat(copy.testTitle()).isNotEqualTo("Notification test");
            assertThat(copy.testBody()).contains("Bitpongo");
        }
        assertThat(NotificationLocaleCopy.find("ja").title(NotificationEventType.TRADE_FAILED)).isEqualTo("取引に失敗しました");
    }
}
