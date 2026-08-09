package com.multind.zhitoubao.notification;

import com.multind.zhitoubao.common.api.BusinessException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpDingTalkClient implements DingTalkClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Predicate<URI> webhookValidator;
    private static final Set<String> ALLOWED_HOSTS = Set.of("oapi.dingtalk.com", "api.dingtalk.com");

    @Autowired
    public HttpDingTalkClient(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json,
                Clock.systemUTC(), HttpDingTalkClient::validateWebhook);
    }

    HttpDingTalkClient(
            HttpClient http, ObjectMapper json, Clock clock, Predicate<URI> webhookValidator) {
        this.http = http; this.json = json; this.clock = clock; this.webhookValidator = webhookValidator;
    }

    @Override
    public Map<String, Object> sendMarkdown(String webhook, String secret, String title, String content) {
        if (webhook == null || webhook.isBlank() || secret == null || secret.isBlank())
            throw new BusinessException(400, "钉钉 Webhook 和签名密钥不能为空");
        long timestamp = clock.millis();
        String separator = webhook.contains("?") ? "&" : "?";
        URI baseUri;
        try {
            baseUri = URI.create(webhook);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(400, "钉钉 Webhook 地址无效");
        }
        if (!webhookValidator.test(baseUri)) {
            throw new BusinessException(400, "钉钉 Webhook 地址无效");
        }
        URI uri = URI.create(webhook + separator + "timestamp=" + timestamp + "&sign="
                + URLEncoder.encode(sign(secret, timestamp), StandardCharsets.UTF_8));
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", title); markdown.put("text", content);
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("atMobiles", List.of()); at.put("isAtAll", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown"); body.put("markdown", markdown); body.put("at", at);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new BusinessException(502, "钉钉通知发送失败");
            Map<String, Object> result = json.readValue(response.body(), new TypeReference<>() {});
            Object errcode = result.get("errcode");
            if (errcode instanceof Number number && number.intValue() != 0)
                throw new BusinessException(502, "钉钉通知发送失败");
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "钉钉通知发送失败");
        } catch (Exception exception) {
            throw new BusinessException(502, "钉钉通知发送失败");
        }
    }

    static String sign(String secret, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signed);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成钉钉签名", exception);
        }
    }

    static boolean validateWebhook(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException(400, "钉钉 Webhook 必须使用 HTTPS");
        }
        if (!ALLOWED_HOSTS.contains(uri.getHost())) {
            throw new BusinessException(400, "钉钉 Webhook 域名不受信任");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException(400, "钉钉 Webhook 不能包含用户信息");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new BusinessException(400, "钉钉 Webhook 只允许 HTTPS 默认端口");
        }
        if (!"/robot/send".equals(uri.getPath())) {
            throw new BusinessException(400, "钉钉 Webhook 路径无效");
        }
        return true;
    }
}
