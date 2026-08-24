package com.multind.bitpongo.notification;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

class BarkLiveSmokeTest {

    private static final String SMOKE_ENV = "BITPONGO_BARK_SMOKE_URL";

    @Test
    @EnabledIfEnvironmentVariable(named = SMOKE_ENV, matches = ".+")
    void sendsOneExplicitlyEnabledBarkTestNotification() {
        String pushUrl = System.getenv(SMOKE_ENV);
        URI supplied = URI.create(pushUrl);
        String authority = authority(supplied);
        BarkProperties properties = new BarkProperties(
                true,
                "",
                Set.of(authority),
                true,
                "",
                false,
                false,
                "");
        BarkPushUrlParser parser = new BarkPushUrlParser(properties);
        BarkTarget target = parser.parse(pushUrl);
        BarkEventPolicy.Policy policy = new BarkEventPolicy()
                .policy(NotificationEventType.BARK_TEST);
        BarkMessage message = new BarkMessage(
                "Bitpongo Bark 接入测试",
                "后端部署联调",
                policy.level(),
                policy.volume(),
                policy.call(),
                policy.sound(),
                policy.group(),
                null);

        new HttpBarkClient(JsonMapper.builder().build(), properties).send(target, message);
    }

    private static String authority(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "invalid-smoke-target";
        }
        String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return uri.getPort() == -1 ? formattedHost : formattedHost + ":" + uri.getPort();
    }
}
