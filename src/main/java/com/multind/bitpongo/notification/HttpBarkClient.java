package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public final class HttpBarkClient implements BarkClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper json;
    private final Function<String, BarkTarget> targetRevalidator;

    @Autowired
    public HttpBarkClient(ObjectMapper json, BarkProperties properties) {
        this(newHttpClient(), json, new BarkPushUrlParser(properties)::parse);
    }

    HttpBarkClient(
            HttpClient http,
            ObjectMapper json,
            Function<String, BarkTarget> targetRevalidator) {
        this.http = Objects.requireNonNull(http, "http");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Bark HttpClient must not follow redirects");
        }
        this.json = Objects.requireNonNull(json, "json");
        this.targetRevalidator = Objects.requireNonNull(targetRevalidator, "targetRevalidator");
    }

    @Override
    public void send(BarkTarget target, BarkMessage message) {
        try {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(message, "message");
            URI endpoint = target.serverUrl().resolve("/push");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(target, message))))
                    .build();

            BarkTarget revalidated = targetRevalidator.apply(pushUrl(target));
            if (!target.equals(revalidated)) {
                throw sendFailure();
            }

            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            verifyResponse(response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw sendFailure();
        } catch (Exception exception) {
            throw sendFailure();
        }
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static Map<String, Object> payload(BarkTarget target, BarkMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_key", target.deviceKey());
        payload.put("title", message.title());
        payload.put("body", message.body());
        payload.put("level", message.level());
        putIfNotNull(payload, "volume", message.volume());
        payload.put("call", message.call());
        putIfNotNull(payload, "sound", message.sound());
        putIfNotNull(payload, "group", message.group());
        putIfNotNull(payload, "url", message.url());
        return payload;
    }

    private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private void verifyResponse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw sendFailure();
        }
        Map<String, Object> result = json.readValue(response.body(), new TypeReference<>() {});
        Object code = result.get("code");
        if (!(code instanceof Number number) || number.intValue() != 200) {
            throw sendFailure();
        }
    }

    private static String pushUrl(BarkTarget target) {
        String serverUrl = target.serverUrl().toASCIIString();
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        String encodedDeviceKey = URLEncoder.encode(target.deviceKey(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return serverUrl + "/" + encodedDeviceKey;
    }

    private static BusinessException sendFailure() {
        return new BusinessException(502, "Bark 通知发送失败");
    }
}
