package com.multind.bitpongo.notification;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BarkLiveSmokeTest {

    private static final String SMOKE_ENV = "BITPONGO_BARK_SMOKE_URL";

    @Test
    void malformedSmokeUrlDoesNotLeakItsValueThroughAnUncaughtFailure() throws IOException, InterruptedException {
        String fakeSecretMarker = "fake-malformed-secret-marker";
        String malformedUrl = "https://api.day.app/" + fakeSecretMarker + "[";
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                MalformedSmokeHarness.class.getName())
                .redirectErrorStream(true);
        builder.environment().put(SMOKE_ENV, malformedUrl);

        Process malformed = builder.start();
        String output = new String(malformed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = malformed.waitFor();

        assertThat(exitCode).isNotZero();
        assertThat(output).doesNotContain(fakeSecretMarker, malformedUrl);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = SMOKE_ENV, matches = ".+")
    void sendsOneExplicitlyEnabledBarkTestNotification() {
        String pushUrl = System.getenv(SMOKE_ENV);
        String authority = safeAuthority(pushUrl);
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

    private static String safeAuthority(String pushUrl) {
        try {
            return authority(URI.create(pushUrl));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Bark smoke URL");
        }
    }

    public static final class MalformedSmokeHarness {
        public static void main(String[] args) {
            new BarkLiveSmokeTest().sendsOneExplicitlyEnabledBarkTestNotification();
        }
    }
}
