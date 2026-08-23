package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpBarkClientTest {

    private final MockWebServer server = new MockWebServer();
    private final JsonMapper json = JsonMapper.builder().build();
    private BarkTarget target;
    private HttpBarkClient client;

    @BeforeEach
    void start() throws Exception {
        server.start();
        target = new BarkTarget(server.url("/").uri(), "fake-device-key");
        client = clientRevalidatingAs(target);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void postsV2JsonWithoutPuttingDeviceKeyInUrl() throws Exception {
        server.enqueue(success());

        client.send(target, new BarkMessage(
                "Bitpongo", "测试", "active", null, false,
                "minuet", "Bitpongo·测试", null));

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/push");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        Map<String, Object> body = json.readValue(
                request.getBody().readUtf8(), new TypeReference<>() {});
        assertThat(body).containsEntry("device_key", "fake-device-key")
                .containsEntry("title", "Bitpongo")
                .containsEntry("body", "测试")
                .containsEntry("level", "active")
                .containsEntry("call", false)
                .containsEntry("sound", "minuet")
                .containsEntry("group", "Bitpongo·测试")
                .doesNotContainKey("volume")
                .doesNotContainKey("url");
    }

    @Test
    void failsClosedBeforeSendingWhenTargetRevalidationChangesTheTarget() {
        BarkTarget changed = new BarkTarget(target.serverUrl(), "different-fake-key");
        HttpBarkClient mismatchingClient = clientRevalidatingAs(changed);

        assertThatThrownBy(() -> mismatchingClient.send(target, message()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("Bark 通知发送失败");
                    assertThat(error.getMessage()).doesNotContain(target.deviceKey());
                });
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void doesNotFollowRedirectResponses() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/redirected"));
        server.enqueue(success());

        assertThatThrownBy(() -> client.send(target, message()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("Bark 通知发送失败");
                });

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/push");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mapsHttpAndBarkFailuresToGenericGatewayError() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        assertGenericFailure(() -> client.send(target, message()));

        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":400,\"message\":\"fake-device-key rejected\"}"));
        assertGenericFailure(() -> client.send(target, message()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"200.5", "4294967496"})
    void rejectsNonIntegerOrOverflowingCodesThatTruncateTo200(String code) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":" + code + ",\"message\":\"not exact integer 200\"}"));
        assertGenericFailure(() -> client.send(target, message()));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private HttpBarkClient clientRevalidatingAs(BarkTarget revalidatedTarget) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new HttpBarkClient(http, json, ignored -> revalidatedTarget);
    }

    private static MockResponse success() {
        return new MockResponse().setResponseCode(200)
                .setBody("{\"code\":200,\"message\":\"success\"}");
    }

    private static BarkMessage message() {
        return new BarkMessage(
                "Bitpongo", "Test", "active", null, false,
                "minuet", "Bitpongo·Test", URI.create("https://app.example.test").toString());
    }

    private static void assertGenericFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable send) {
        assertThatThrownBy(send)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("Bark 通知发送失败");
                    assertThat(error.getMessage()).doesNotContain("fake-device-key");
                });
    }
}
