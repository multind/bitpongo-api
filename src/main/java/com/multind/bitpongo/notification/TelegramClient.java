package com.multind.bitpongo.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class TelegramClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String token;
    private final String bot;
    private final String secret;
    private final ObjectMapper json;

    TelegramClient(@Value("${TELEGRAM_BOT_TOKEN:}") String token,
            @Value("${TELEGRAM_BOT_USERNAME:}") String bot,
            @Value("${TELEGRAM_WEBHOOK_SECRET:}") String secret, ObjectMapper json) {
        this.token = token;
        this.bot = bot;
        this.secret = secret;
        this.json = json;
    }

    boolean available() {
        return token.matches("[0-9]+:[A-Za-z0-9_-]+") && bot.matches("[A-Za-z0-9_]{5,32}")
                && secret.matches("[A-Za-z0-9_-]{32,256}");
    }

    String link(String code) { return "https://t.me/" + bot + "?start=" + code; }

    boolean authentic(String value) {
        return available() && value != null && java.security.MessageDigest.isEqual(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    boolean send(long chat, String text) {
        if (!available()) return false;
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(6)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "chat_id", chat, "text", text, "link_preview_options", Map.of("is_disabled", true))))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && json.readTree(response.body()).path("ok").asBoolean(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // The request URI contains the token; never log exception text.
            return false;
        }
    }
}
