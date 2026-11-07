package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=webhook-settings-test-secret",
        "zhitoubao.notifications.bark.dispatch-enabled=false",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false",
        "zhitoubao.notifications.outbound-webhook.enabled=true",
        "zhitoubao.notifications.outbound-webhook.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class WebhookReminderSettingsIntegrationTest {
    private static final String URL = "https://oapi.dingtalk.com/robot/send?access_token=sample_token";
    private static final String SECRET = "sample-signing-secret";
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(System.getProperty("test.mysql.image", "mysql:9.7.0"));

    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookReminderService service;
    @MockitoBean WebhookReminderClient client;

    @Test void settingsDoNotExposeCredentialsAndOnlyTheOwnerCanManageThem() {
        long owner = account(), other = account();
        long id = create(owner);
        var item = service.list(owner).items().getFirst();
        assertThat(item.enabled()).isFalse();
        assertThat(item.maskedUrl()).isEqualTo("https://oapi.dingtalk.com/…");
        assertThat(item.toString()).doesNotContain(URL, SECRET);
        assertThat(jdbc.queryForMap("select url_ciphertext, secret_ciphertext from outbound_webhook_destination where id=?", id)
                .values()).allMatch(value -> value.toString().startsWith("v1:"));
        assertThat(service.list(other).items()).isEmpty();
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.update(other, id, patch(true, null)));
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.delete(other, id));
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.test(other, id));
        verifyNoInteractions(client);
        service.update(owner, id, patch(true, null));
        assertThat(service.list(owner).items().getFirst().enabled()).isTrue();
        when(client.send(WebhookReminderProvider.DINGTALK, URL, SECRET, "Bitpongo: Webhook test notification"))
                .thenReturn(WebhookReminderClient.Result.SENT);
        service.test(owner, id);
        service.update(owner, id, patch(null, ""));
        assertThat(jdbc.queryForObject("select secret_ciphertext from outbound_webhook_destination where id=?", String.class, id))
                .isNull();
        service.delete(owner, id);
        assertThat(service.list(owner).items()).isEmpty();
    }

    @Test void failedTestStillConsumesThePerUserLimitAcrossDestinations() {
        long owner = account(), other = account();
        long a = create(owner), b = create(owner), c = create(other);
        when(client.send(WebhookReminderProvider.DINGTALK, URL, SECRET, "Bitpongo: Webhook test notification"))
                .thenReturn(WebhookReminderClient.Result.REJECTED, WebhookReminderClient.Result.SENT);
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.test(owner, a))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(502));
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.test(owner, b))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(429));
        service.test(other, c);
        verify(client, times(2)).send(WebhookReminderProvider.DINGTALK, URL, SECRET, "Bitpongo: Webhook test notification");
    }

    @Test void unreadableCredentialsNeverReachTheProvider() {
        long owner = account(), id = create(owner);
        jdbc.update("update outbound_webhook_destination set url_ciphertext='invalid-envelope' where id=?", id);
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.test(owner, id))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error).hasMessage("Webhook test notification could not be delivered");
                });
        verifyNoInteractions(client);
    }

    private long account() {
        String email = UUID.randomUUID() + "@example.com";
        jdbc.update("insert into user(name,email,password) values('webhook',?,'unused')", email);
        return jdbc.queryForObject("select id from user where email=?", Long.class, email);
    }

    private long create(long owner) {
        return service.create(owner, new WebhookReminderService.Draft("DINGTALK", "My channel", URL, SECRET, true));
    }

    private WebhookReminderService.Draft patch(Boolean enabled, String secret) {
        return new WebhookReminderService.Draft(null, null, null, secret, enabled);
    }
}
