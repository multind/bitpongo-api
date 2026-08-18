package com.multind.bitpongo.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multind.bitpongo.common.api.BusinessException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpWordPressAuthClient implements WordPressAuthClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final HttpClient http;
    private final Duration requestTimeout;

    @Autowired
    public HttpWordPressAuthClient(
            @Value("${zhitoubao.wordpress.endpoint-url:https://multind.com}") String endpoint,
            @Value("${zhitoubao.wordpress.connect-timeout:5s}") Duration connectTimeout,
            @Value("${zhitoubao.wordpress.read-timeout:10s}") Duration readTimeout) {
        this.endpoint = URI.create(stripTrailingSlash(endpoint));
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = readTimeout;
    }

    @Override
    public WordPressSession login(String username, String password) {
        try {
            String body = JSON.writeValueAsString(Map.of("username", username, "password", password));
            HttpResponse<String> response = send(HttpRequest.newBuilder(resolve("/wp-json/jwt-auth/v1/token"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BusinessException(401, "登录失败，请检查用户名和密码");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, "无法连接到服务器");
            }
            JsonNode data = JSON.readTree(response.body());
            String token = requiredText(data, "token", "服务器返回的认证信息格式错误");
            String email = data.path("user_email").asText(username);
            String displayName = data.path("user_display_name").asText("");
            long userId = data.path("data").path("user").path("id").asLong(0L);
            if (userId == 0L) {
                userId = decodeWordPressUserId(token);
            }
            if (userId == 0L) {
                throw new BusinessException(500, "返回 token 格式错误");
            }
            return new WordPressSession(token, userId, email, displayName);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(500, "无法连接到服务器");
        }
    }

    @Override
    public AuthenticatedUser resolveUser(String token) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(resolve("/wp-json/wp/v2/users/me"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BusinessException(401, "无法验证凭据");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, "无法连接到服务器");
            }
            JsonNode data = JSON.readTree(response.body());
            long id = data.path("id").asLong(0L);
            if (id == 0L) {
                throw new BusinessException(401, "无法验证凭据");
            }
            return new AuthenticatedUser(id, data.path("email").asText(null), data.path("name").asText(null));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(500, "无法连接到服务器");
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("WordPress request interrupted", exception);
        }
    }

    private URI resolve(String path) {
        return URI.create(endpoint + path);
    }

    private static long decodeWordPressUserId(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return 0L;
            }
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return payload.path("data").path("user").path("id").asLong(0L);
        } catch (RuntimeException | IOException exception) {
            return 0L;
        }
    }

    private static String requiredText(JsonNode node, String field, String message) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new BusinessException(500, message);
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        String stripped = value == null ? "" : value.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.isBlank()) {
            throw new IllegalArgumentException("WORDPRESS_ENDPOINT_URL 未配置");
        }
        return stripped;
    }
}
