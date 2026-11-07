package com.multind.bitpongo.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookReminderClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC);

    @Test
    void sendsProviderSpecificPayloadsWithoutMentioningDiscordUsers() {
        var payload = new AtomicReference<String>();
        var client = new WebhookReminderClient(json, clock, (url, body) -> {
            payload.set(body);
            return new WebhookReminderClient.Response(200, switch (url.getHost()) {
                case "oapi.dingtalk.com" -> "{\"errcode\":0}";
                case "open.feishu.cn" -> "{\"code\":0}";
                case "hooks.slack.com" -> "ok";
                default -> "{\"id\":\"123\"}";
            });
        });
        assertThat(client.send(WebhookReminderProvider.DINGTALK, "https://oapi.dingtalk.com/robot/send?access_token=sample", null, "Trade"))
                .isEqualTo(WebhookReminderClient.Result.SENT);
        assertThat(json.readTree(payload.get()).path("text").path("content").asText()).isEqualTo("Trade");
        assertThat(client.send(WebhookReminderProvider.FEISHU, "https://open.feishu.cn/open-apis/bot/v2/hook/sample", null, "Trade"))
                .isEqualTo(WebhookReminderClient.Result.SENT);
        assertThat(json.readTree(payload.get()).path("content").path("text").asText()).isEqualTo("Trade");
        assertThat(client.send(WebhookReminderProvider.SLACK, "https://hooks.slack.com/services/T1/B1/token", null, "Trade"))
                .isEqualTo(WebhookReminderClient.Result.SENT);
        assertThat(json.readTree(payload.get()).path("text").asText()).isEqualTo("Trade");
        assertThat(client.send(WebhookReminderProvider.DISCORD, "https://discord.com/api/webhooks/123/token", null, "@everyone"))
                .isEqualTo(WebhookReminderClient.Result.SENT);
        assertThat(json.readTree(payload.get()).path("allowed_mentions").path("parse").isArray()).isTrue();
        assertThat(json.readTree(payload.get()).path("allowed_mentions").path("parse").size()).isZero();
    }

    @Test
    void signsBothRobotRequestsAndDistinguishesRetryFromRejection() {
        var client = new WebhookReminderClient(json, clock, (url, body) -> {
            if (url.getHost().equals("oapi.dingtalk.com")) {
                assertThat(url.getQuery()).isEqualTo(
                        "access_token=sample&timestamp=1700000000000&sign=+9T2O00qq5I53Jy5SYLwGf626AwxLjYwAt9tO1ELk9c=");
                return new WebhookReminderClient.Response(429, "");
            }
            var payload = json.readTree(body);
            assertThat(payload.path("timestamp").asText()).isEqualTo("1700000000");
            assertThat(payload.path("sign").asText()).isEqualTo("HsyfQO1P0UCajraMQX1oZdxcKNMCR4IrjjzIVqAXhEw=");
            return new WebhookReminderClient.Response(200, "{\"code\":19021}");
        });
        assertThat(client.send(WebhookReminderProvider.DINGTALK, "https://oapi.dingtalk.com/robot/send?access_token=sample", "signing-secret", "Trade"))
                .isEqualTo(WebhookReminderClient.Result.RETRY);
        assertThat(client.send(WebhookReminderProvider.FEISHU, "https://open.feishu.cn/open-apis/bot/v2/hook/sample", "signing-secret", "Trade"))
                .isEqualTo(WebhookReminderClient.Result.REJECTED);
    }

    @Test
    void rejectsRedirectAndBadResponseButRetriesNetworkFailures() {
        var provider = WebhookReminderProvider.SLACK;
        String address = "https://hooks.slack.com/services/T1/B1/token";
        assertThat(new WebhookReminderClient(json, clock, (url, body) -> new WebhookReminderClient.Response(302, "ok"))
                .send(provider, address, null, "Trade")).isEqualTo(WebhookReminderClient.Result.REJECTED);
        assertThat(new WebhookReminderClient(json, clock, (url, body) -> new WebhookReminderClient.Response(200, "invalid"))
                .send(provider, address, null, "Trade")).isEqualTo(WebhookReminderClient.Result.REJECTED);
        assertThat(new WebhookReminderClient(json, clock, (url, body) -> { throw new java.io.IOException("token"); })
                .send(provider, address, null, "Trade")).isEqualTo(WebhookReminderClient.Result.RETRY);
    }

    @Test
    void acceptsOnlyProviderSpecificWebhookEndpoints() {
        assertThat(WebhookReminderProvider.DINGTALK.validate(
                "https://oapi.dingtalk.com/robot/send?access_token=sample").getHost()).isEqualTo("oapi.dingtalk.com");
        assertThat(WebhookReminderProvider.FEISHU.validate(
                "https://open.feishu.cn/open-apis/bot/v2/hook/sample").getHost()).isEqualTo("open.feishu.cn");
        assertThat(WebhookReminderProvider.SLACK.validate(
                "https://hooks.slack.com/services/T1/B1/token").getHost()).isEqualTo("hooks.slack.com");
        assertThat(WebhookReminderProvider.DISCORD.validate(
                "https://discord.com/api/webhooks/123/token").getHost()).isEqualTo("discord.com");
    }

    @Test
    void rejectsHostTricksAndDoesNotEchoSecrets() {
        for (String url : new String[] {
                "http://hooks.slack.com/services/T1/B1/secret",
                "https://hooks.slack.com.evil.test/services/T1/B1/secret",
                "https://evil.test@hooks.slack.com/services/T1/B1/secret",
                "https://hooks.slack.com:444/services/T1/B1/secret",
                "https://hooks.slack.com/services/T1/B1/%2fsecret",
                "https://hooks.slack.com/services/T1/B1/secret?redirect=1",
                "https://hooks.slack.com/services/T1/B1/secret#fragment",
                "https://hooks.slack.com/secret"
        }) {
            assertThatThrownBy(() -> WebhookReminderProvider.SLACK.validate(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret");
        }
    }
}
