package com.multind.bitpongo.notification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class WebhookReminderClient {
    enum Result { SENT, RETRY, REJECTED }

    record Response(int status, String body) {}

    @FunctionalInterface
    interface Transport {
        Response post(URI destination, String body) throws IOException, InterruptedException;
    }

    private final ObjectMapper json;
    private final Clock clock;
    private final Transport transport;

    @Autowired
    WebhookReminderClient(ObjectMapper json) {
        this(json, Clock.systemUTC(), httpTransport());
    }

    WebhookReminderClient(ObjectMapper json, Clock clock, Transport transport) {
        this.json = json;
        this.clock = clock;
        this.transport = transport;
    }

    Result send(WebhookReminderProvider provider, String address, String signingSecret, String text) {
        try {
            URI url = provider.validate(address);
            if (text == null || text.isBlank()) return Result.REJECTED;
            int limit = Math.min(1900, text.length());
            if (Character.isHighSurrogate(text.charAt(limit - 1))) limit--;
            String message = text.substring(0, limit);
            var body = new HashMap<String, Object>();
            switch (provider) {
                case DINGTALK -> {
                    body.put("msgtype", "text");
                    body.put("text", Map.of("content", message));
                    if (signingSecret != null && !signingSecret.isBlank()) {
                        long timestamp = clock.millis();
                        String sign = hmac(signingSecret, timestamp + "\n" + signingSecret);
                        url = URI.create(url + "&timestamp=" + timestamp + "&sign="
                                + URLEncoder.encode(sign, StandardCharsets.UTF_8));
                    }
                }
                case FEISHU -> {
                    body.put("msg_type", "text");
                    body.put("content", Map.of("text", message));
                    if (signingSecret != null && !signingSecret.isBlank()) {
                        String timestamp = Long.toString(clock.instant().getEpochSecond());
                        body.put("timestamp", timestamp);
                        body.put("sign", hmac(timestamp + "\n" + signingSecret, ""));
                    }
                }
                case SLACK -> {
                    body.put("text", message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
                    body.put("mrkdwn", false);
                    body.put("unfurl_links", false);
                    body.put("unfurl_media", false);
                }
                case DISCORD -> {
                    url = URI.create(url + "?wait=true");
                    body.put("content", message);
                    body.put("allowed_mentions", Map.of("parse", List.of()));
                }
            }
            return classify(provider, transport.post(url, json.writeValueAsString(body)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.RETRY;
        } catch (IOException networkFailure) {
            return Result.RETRY;
        } catch (RuntimeException | GeneralSecurityException invalid) {
            // The request URI or provider response may contain credentials.
            return Result.REJECTED;
        }
    }

    private Result classify(WebhookReminderProvider provider, Response response) {
        int status = response.status();
        if (status == 429 || status >= 500) return Result.RETRY;
        if (status < 200 || status >= 300) return Result.REJECTED;
        boolean accepted = switch (provider) {
            case DINGTALK -> json.readTree(response.body()).path("errcode").asInt(-1) == 0;
            case FEISHU -> json.readTree(response.body()).path("code").asInt(-1) == 0;
            case SLACK -> "ok".equals(response.body().strip());
            case DISCORD -> json.readTree(response.body()).path("id").asText("").matches("[0-9]+");
        };
        return accepted ? Result.SENT : Result.REJECTED;
    }

    private static String hmac(String key, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (url, body) -> {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, ignored -> HttpResponse.BodySubscribers.limiting(
                    HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 16_384));
            return new Response(response.statusCode(), response.body());
        };
    }
}
