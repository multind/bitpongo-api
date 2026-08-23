package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessageRendererTest {

    private final NotificationMessageRenderer renderer =
            new NotificationMessageRenderer(new BarkEventPolicy());

    @ParameterizedTest
    @MethodSource("localizedMessages")
    void rendersApprovedLocaleTextAndIanaTimezone(
            String locale, String timezone, String expectedTitle, String expectedBody) {
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.TRADE_FAILED,
                7L,
                11L,
                13L,
                Instant.parse("2026-08-23T01:02:03Z"),
                "trade-failed-11",
                Map.of("symbol", "BTCUSDT", "status", "REJECTED", "error", "余额不足"));

        BarkMessage message = renderer.render(event, locale, timezone, "https://app.example.test/plans/11");

        assertThat(message.title()).isEqualTo(expectedTitle);
        assertThat(message.body()).isEqualTo(expectedBody);
        assertThat(message.level()).isEqualTo("timeSensitive");
        assertThat(message.call()).isFalse();
        assertThat(message.group()).isEqualTo("Bitpongo·交易异常");
    }

    @Test
    void redactsUrisCredentialsAndBearerTokensBeforeTruncatingErrorSummary() {
        String secretTail = "x".repeat(400);
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.SCHEDULER_FATAL,
                null,
                null,
                null,
                Instant.parse("2026-08-23T01:02:03Z"),
                "scheduler-fatal",
                Map.of("error", "POST https://api.example.test/orders?access_token=fake-uri-token "
                        + "Bearer fake-bearer-token accessKey=fake-access secret_key: fake-secret "
                        + "token=fake-token key=fake-key secret fake-space-secret "
                        + "user@example.test " + secretTail));

        BarkMessage message = renderer.render(event, "zh-CN", "UTC", null);

        assertThat(message.body()).doesNotContain(
                "https://", "fake-uri-token", "fake-bearer-token", "fake-access",
                "fake-secret", "fake-token", "fake-key", "fake-space-secret",
                "user@example.test");
        String errorLine = message.body().lines()
                .filter(line -> line.startsWith("错误："))
                .findFirst()
                .orElseThrow();
        assertThat(errorLine.substring("错误：".length())).hasSize(300);
    }

    @Test
    void recoveryKeepsOriginalAlertGroupWithoutRestoringItsRingingPolicy() {
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.SYSTEM_RECOVERED,
                7L,
                null,
                null,
                Instant.parse("2026-08-23T01:02:03Z"),
                "market-recovered-cycle-1",
                Map.of("originalEventType", "MARKET_OUTAGE"));

        BarkMessage message = renderer.render(event, "zh-CN", "Asia/Shanghai", null);

        assertThat(message.group()).isEqualTo("Bitpongo·行情");
        assertThat(message.level()).isEqualTo("passive");
        assertThat(message.sound()).isNull();
        assertThat(message.call()).isFalse();
    }

    @Test
    void redactsCompoundCredentialFieldNamesFromRenderedErrorText() {
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.TRADE_FAILED,
                7L,
                11L,
                null,
                Instant.parse("2026-08-23T01:02:03Z"),
                "trade-failed-compound-credentials",
                Map.of("error", "api_key=fake-api-key client_secret: fake-client-secret "
                        + "refresh_token fake-refresh-token"));

        BarkMessage message = renderer.render(event, "en-US", "UTC", null);

        assertThat(message.body())
                .doesNotContain("fake-api-key", "fake-client-secret", "fake-refresh-token")
                .contains("api_key=<redacted>", "client_secret=<redacted>",
                        "refresh_token <redacted>");
    }

    private static Stream<Arguments> localizedMessages() {
        return Stream.of(
                Arguments.of("zh-CN", "Asia/Shanghai", "交易失败",
                        "时间：2026-08-23 09:02:03 Asia/Shanghai\n"
                                + "用户 ID：7\n计划 ID：11\n订单 Intent ID：13\n"
                                + "币种：BTCUSDT\n结果：REJECTED\n错误：余额不足"),
                Arguments.of("zh-TW", "Asia/Taipei", "交易失敗",
                        "時間：2026-08-23 09:02:03 Asia/Taipei\n"
                                + "使用者 ID：7\n計畫 ID：11\n訂單 Intent ID：13\n"
                                + "幣種：BTCUSDT\n結果：REJECTED\n錯誤：余额不足"),
                Arguments.of("en-US", "America/New_York", "Trade failed",
                        "Time: 2026-08-22 21:02:03 America/New_York\n"
                                + "User ID: 7\nPlan ID: 11\nOrder Intent ID: 13\n"
                                + "Symbol: BTCUSDT\nResult: REJECTED\nError: 余额不足"));
    }
}
