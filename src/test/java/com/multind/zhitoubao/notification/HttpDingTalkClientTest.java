package com.multind.zhitoubao.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpDingTalkClientTest {
    private final MockWebServer server = new MockWebServer();
    private boolean started;

    @AfterEach void stop() throws Exception { if (started) server.shutdown(); }

    @Test
    void signsTimestampAndSendsCompatibleMarkdown() throws Exception {
        server.start();
        started = true;
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        long timestamp = Instant.parse("2026-08-09T00:00:00Z").toEpochMilli();
        HttpDingTalkClient client = new HttpDingTalkClient(java.net.http.HttpClient.newHttpClient(),
                JsonMapper.builder().build(), Clock.fixed(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC),
                ignored -> true);

        Map<String, Object> response = client.sendMarkdown(
                server.url("/robot/send?access_token=x").toString(), "secret", "智投宝通知", "测试内容");

        var request = server.takeRequest();
        assertThat(request.getRequestUrl().queryParameter("timestamp")).isEqualTo(Long.toString(timestamp));
        assertThat(request.getRequestUrl().queryParameter("sign")).isEqualTo(sign("secret", timestamp));
        assertThat(request.getBody().readUtf8()).contains("\"msgtype\":\"markdown\"", "智投宝通知", "测试内容");
        assertThat(response.get("errcode")).isEqualTo(0);
    }

    @Test
    void rejectsNonDingTalkAndPlaintextWebhookTargets() {
        assertThatThrownBy(() -> HttpDingTalkClient.validateWebhook(
                java.net.URI.create("http://oapi.dingtalk.com/robot/send?access_token=x")))
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> HttpDingTalkClient.validateWebhook(
                java.net.URI.create("https://127.0.0.1/robot/send?access_token=x")))
                .hasMessageContaining("域名");
        assertThatThrownBy(() -> HttpDingTalkClient.validateWebhook(
                java.net.URI.create("https://oapi.dingtalk.com:8443/robot/send?access_token=x")))
                .hasMessageContaining("端口");
    }

    private String sign(String secret, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(
                (timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
    }
}
