package com.multind.bitpongo.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BarkDocumentationContractTest {

    private static final Path ENV = Path.of(".env.example");
    private static final Path COMPOSE = Path.of("compose.yml");
    private static final Path README = Path.of("README.md");
    private static final Path MATRIX = Path.of("docs/python-java-contract-matrix.md");
    private static final Path APPLICATION = Path.of("src/main/resources/application.yml");

    @Test
    void exampleEnvironmentUsesSafeBarkDefaultsAndEmptySecrets() throws IOException {
        Map<String, String> values = dotenv(Files.readString(ENV));

        assertThat(values).containsEntry("BARK_USER_NOTIFICATIONS_ENABLED", "true")
                .containsEntry("BARK_ADMIN_PUSH_URL", "")
                .containsEntry("BARK_ALLOWED_HOSTS", "api.day.app")
                .containsEntry("BARK_ALLOW_PRIVATE_HOSTS", "false")
                .containsEntry("BARK_CREDENTIAL_ENCRYPTION_KEY", "")
                .containsEntry("BARK_NOTIFY_ON_STARTUP", "false")
                .containsEntry("BARK_DISPATCH_ENABLED", "true")
                .containsEntry("APP_PUBLIC_URL", "");
    }

    @Test
    void composeMapsEveryBarkDeploymentVariable() throws IOException {
        String compose = Files.readString(COMPOSE);

        assertThat(compose).contains(
                "BARK_USER_NOTIFICATIONS_ENABLED:",
                "BARK_ADMIN_PUSH_URL:",
                "BARK_ALLOWED_HOSTS:",
                "BARK_ALLOW_PRIVATE_HOSTS:",
                "BARK_CREDENTIAL_ENCRYPTION_KEY:",
                "BARK_NOTIFY_ON_STARTUP:",
                "BARK_DISPATCH_ENABLED:",
                "APP_PUBLIC_URL:");
    }

    @Test
    void applicationBindingsMatchDocumentedEnvironmentVariables() throws IOException {
        String application = Files.readString(APPLICATION);

        assertThat(application).contains(
                "${BARK_USER_NOTIFICATIONS_ENABLED:true}",
                "${BARK_ADMIN_PUSH_URL:}",
                "${BARK_ALLOWED_HOSTS:api.day.app}",
                "${BARK_ALLOW_PRIVATE_HOSTS:false}",
                "${BARK_CREDENTIAL_ENCRYPTION_KEY:}",
                "${BARK_NOTIFY_ON_STARTUP:false}",
                "${BARK_DISPATCH_ENABLED:true}",
                "${APP_PUBLIC_URL:}");
    }

    @Test
    void readmeDocumentsBarkSecurityOperationsAndDeliverySemantics() throws IOException {
        String readme = Files.readString(README);

        assertThat(readme).contains(
                "openssl rand -base64 32",
                "Bark URL",
                "Device Key",
                "BARK_ALLOWED_HOSTS",
                "BARK_ALLOW_PRIVATE_HOSTS=false",
                "GET /api/users/notifications/bark",
                "PUT /api/users/notifications/bark",
                "DELETE /api/users/notifications/bark",
                "POST /api/users/notifications/bark/test",
                "Outbox",
                "重试",
                "去重",
                "BITPONGO_BARK_SMOKE_URL",
                "call=1");
        assertThat(readme).contains("SCHEDULER_FATAL", "ORDER_MANUAL_REVIEW", "critical");
    }

    @Test
    void controlledDeploymentDocumentsContainNoRemovedNotificationContract() throws IOException {
        String controlled = String.join("\n",
                Files.readString(ENV), Files.readString(COMPOSE),
                Files.readString(README), Files.readString(MATRIX));
        String removedVendor = "ding" + "talk";
        String removedChineseVendor = "\u9489\u9489";
        String removedSettingRoute = "/api/users/" + "ding";
        String removedNoticeRoute = "/api/users/" + "notices";

        assertThat(controlled).doesNotContainIgnoringCase(removedVendor)
                .doesNotContain(removedChineseVendor, removedSettingRoute, removedNoticeRoute);
    }

    private static Map<String, String> dotenv(String content) {
        return Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }
}
