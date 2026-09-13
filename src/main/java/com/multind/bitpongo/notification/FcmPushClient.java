package com.multind.bitpongo.notification;

import com.google.auth.oauth2.ServiceAccountCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Small HTTP v1 transport; all retries belong to the durable outbox. Never logs provider bodies or tokens. */
@Component
public class FcmPushClient implements NativePushClient {
    private final ServiceAccountCredentials credentials;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final java.util.concurrent.ExecutorService refreshExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private java.util.concurrent.Future<Boolean> refresh;

    public FcmPushClient(@Value("${FCM_ENABLED:false}") boolean enabled,
            @Value("${FCM_PROJECT_ID:}") String project,
            @Value("${GOOGLE_APPLICATION_CREDENTIALS:}") String path) {
        ServiceAccountCredentials loaded = null;
        URI target = null;
        if (enabled && project.matches("[a-z][a-z0-9-]{4,62}") && !path.isBlank()) {
            try (var input = Files.newInputStream(Path.of(path))) {
                loaded = (ServiceAccountCredentials) ServiceAccountCredentials.fromStream(input)
                        .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
                target = URI.create("https://fcm.googleapis.com/v1/projects/" + project + "/messages:send");
            } catch (Exception ignored) {
                // Missing/malformed credentials deliberately leave all other application features usable.
            }
        }
        credentials = loaded;
        endpoint = target;
    }
    @Override public boolean available() { return credentials != null && endpoint != null; }
    /** OAuth refresh occurs before the dispatcher takes any account/device lock, with a bounded wait. */
    @Override public synchronized boolean prepare() {
        if (!available()) return false;
        if (credentials.getAccessToken() != null && credentials.getAccessToken().getExpirationTime() != null
                && credentials.getAccessToken().getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(60))) return true;
        if (refresh == null || refresh.isDone()) refresh = refreshExecutor.submit(() -> {
            try { credentials.refreshIfExpired(); return credentials.getAccessToken() != null; }
            catch (Exception ignored) { return false; }
        });
        try { return refresh.get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception ignored) { return false; }
    }
    @jakarta.annotation.PreDestroy void close() { refreshExecutor.shutdownNow(); }
    @Override public Result send(String token, Map<String, Object> message) {
        if (!available()) return new Result(false, true, false, 60, "UNAVAILABLE_CONFIG");
        try {
            if (credentials.getAccessToken() == null) return new Result(false,true,false,60,"AUTH_NOT_READY");
            Map<String, Object> body = new LinkedHashMap<>(message);
            body.put("token", token);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("message", body))))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300)
                return new Result(true, false, false, 0, "ACCEPTED");
            String code = "PROVIDER_REJECTED";
            try {
                var error = json.readTree(response.body()).path("error");
                code = error.path("status").asText("PROVIDER_REJECTED");
                for (var detail : error.path("details")) {
                    if (detail.path("@type").asText().equals("type.googleapis.com/google.firebase.fcm.v1.FcmError"))
                        code = detail.path("errorCode").asText(code);
                }
            } catch (Exception ignored) { /* classify by HTTP status if body is not JSON */ }
            return classify(response.statusCode(), code,
                    retryAfter(response.headers().firstValue("Retry-After").orElse("")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, true, false, 60, "INTERRUPTED");
        } catch (Exception e) {
            return new Result(false, true, false, 60, "TRANSPORT_ERROR");
        }
    }
    static Result classify(int status, String code, long retryAfter) {
        boolean invalid = status == 404 && "UNREGISTERED".equals(code);
        boolean retry = status == 429 || status >= 500;
        String safe = switch (code) {
            case "UNREGISTERED", "INVALID_ARGUMENT", "QUOTA_EXCEEDED", "UNAVAILABLE", "INTERNAL",
                    "SENDER_ID_MISMATCH", "THIRD_PARTY_AUTH_ERROR" -> code;
            default -> "PROVIDER_REJECTED";
        };
        return new Result(false, retry, invalid, retry ? Math.max(60, retryAfter) : 0, safe);
    }
    static long retryAfter(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException e) {
            try { return Math.max(0, Duration.between(Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()); }
            catch (Exception ignored) { return 0; }
        }
    }
}
