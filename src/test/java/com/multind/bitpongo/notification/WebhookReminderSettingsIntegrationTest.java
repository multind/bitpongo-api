package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
        "OUTBOUND_WEBHOOK_DISPATCH_INITIAL_DELAY=24h",
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
    @MockitoSpyBean WebhookReminderOutbox outbox;
    @Autowired WebhookReminderDispatcher dispatcher;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired NotificationPerceptionQueue perceptionQueue;
    @Autowired com.multind.bitpongo.alerts.AlertEventService alertEvents;
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

    @Test void durableFanoutIsExplicitOwnerScopedDeduplicatedAndIndependent() {
        long owner = account(), other = account();
        long a = create(owner), b = create(owner), c = create(other);
        service.update(owner, a, patch(true, null));
        service.update(owner, b, patch(true, null));
        service.update(other, c, patch(true, null));
        var price = event(owner, NotificationEventType.PRICE_ALERT_TRIGGERED);
        outbox.enqueue(price);
        outbox.enqueue(event(owner, NotificationEventType.SERVICE_STARTED));
        assertThat(jdbc.queryForObject("select count(*) from outbound_webhook_delivery", Integer.class)).isZero();
        outbox.enqueuePrice(price);
        outbox.enqueuePrice(price);
        assertThat(jdbc.queryForList("select destination_id from outbound_webhook_delivery order by destination_id", Long.class))
                .containsExactly(a, b);
        when(client.send(eq(WebhookReminderProvider.DINGTALK), eq(URL), eq(SECRET), anyString()))
                .thenReturn(WebhookReminderClient.Result.RETRY, WebhookReminderClient.Result.SENT);
        dispatcher.dispatch();
        assertThat(jdbc.queryForList("select status from outbound_webhook_delivery order by destination_id", String.class))
                .containsExactly("RETRY", "SENT");
        service.update(owner, a, patch(false, null));
        assertThat(jdbc.queryForObject("select status from outbound_webhook_delivery where destination_id=?", String.class, a))
                .isEqualTo("SKIPPED");
        verify(client, times(2)).send(eq(WebhookReminderProvider.DINGTALK), eq(URL), eq(SECRET), anyString());
    }

    @Test void webhookFanoutRunsOnlyAfterCommitAndCannotUndoTheOriginatingWrite() {
        long owner = account();
        var event = event(owner, NotificationEventType.PRICE_ALERT_TRIGGERED);
        var candidate = new WebhookReminderEventListener.Candidate(event, true);
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            events.publishEvent(candidate);
            status.setRollbackOnly();
        });
        verify(outbox, never()).enqueuePrice(event);
        doThrow(new IllegalStateException("test fanout failure")).when(outbox).enqueuePrice(event);
        tx.executeWithoutResult(status -> {
            jdbc.update("update user set name='committed' where id=?", owner);
            events.publishEvent(candidate);
            verify(outbox, never()).enqueuePrice(event);
        });
        verify(outbox).enqueuePrice(event);
        assertThat(jdbc.queryForObject("select name from user where id=?", String.class, owner)).isEqualTo("committed");
    }

    @Test void accountSwitchCancelsPendingDeliveryWithoutChangingDestinations() {
        long owner = account(), other = account(), id = create(owner);
        service.update(owner, id, patch(true, null));
        var first = event(owner, NotificationEventType.TRADE_SUCCEEDED);
        outbox.enqueue(first);
        service.setEnabled(owner, false);
        assertThat(service.list(owner).enabled()).isFalse();
        assertThat(service.list(other).enabled()).isTrue();
        assertThat(service.list(owner).items().getFirst().enabled()).isTrue();
        var second = event(owner, NotificationEventType.TRADE_SUCCEEDED);
        outbox.enqueue(second);
        assertThat(jdbc.queryForList("select status from outbound_webhook_delivery where destination_id=?", String.class, id))
                .containsExactly("SKIPPED");
        service.setEnabled(owner, true);
        outbox.enqueue(second);
        assertThat(jdbc.queryForList("select status from outbound_webhook_delivery where destination_id=? order by id", String.class, id))
                .containsExactly("SKIPPED", "PENDING");
        jdbc.update("delete from user where id=?", owner);
    }

    @Test void perceptionQueueWaitsFiveMinutesBetweenTiersAndStopsAtConfirmation() {
        long owner = account();
        jdbc.update("""
                insert into user_notification_channel(user_id,confirmation_enabled,updated_at)
                values (?,true,UTC_TIMESTAMP(6))
                """, owner);
        String key = UUID.randomUUID().toString();
        var now = java.time.LocalDateTime.of(2026, 11, 8, 10, 0);
        jdbc.update("""
                insert into alert_event(user_id,event_type,event_key,title,body,occurred_at)
                values (?,'PRICE_ALERT_TRIGGERED',?,'Alert','BTC',?)
                """, owner, key, now);
        long id = jdbc.queryForObject("select id from alert_event where event_key=?", Long.class, key);
        var tiers = java.util.List.of(java.util.List.of("email"), java.util.List.of("telegram", "webhook"), java.util.List.of("bitpongo"));
        var sent = new java.util.ArrayList<java.util.List<String>>();
        java.util.function.Consumer<NotificationPerceptionQueue.Wave> dispatch = wave -> sent.add(wave.channels());
        perceptionQueue.schedule(owner, id, tiers, true, now);
        perceptionQueue.schedule(owner, id, tiers, true, now);
        assertThat(perceptionQueue.advance(now, dispatch)).isTrue();
        assertThat(perceptionQueue.advance(now.plusSeconds(299), dispatch)).isFalse();
        assertThat(sent).containsExactly(java.util.List.of("email"));
        assertThat(perceptionQueue.advance(now.plusMinutes(5), dispatch)).isTrue();
        assertThat(sent).containsExactly(java.util.List.of("email"), java.util.List.of("telegram", "webhook"));
        assertThat(alertEvents.list(owner, false, 0, 10).items().getFirst().confirmationRequired()).isTrue();
        alertEvents.confirm(owner, id);
        assertThat(perceptionQueue.advance(now.plusMinutes(10), dispatch)).isFalse();
        assertThat(jdbc.queryForObject("select state from notification_perception_wave where event_id=? and stage=2", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(sent).hasSize(2);
        assertThat(perceptionQueue.advance(now.plusMinutes(15), dispatch)).isFalse();
    }

    private NotificationEvent event(long owner, NotificationEventType type) {
        return new NotificationEvent(type, owner, null, null, java.time.Instant.now(),
                UUID.randomUUID().toString(), java.util.Map.of("symbol", "BTCUSDT", "status", "secret_key=do-not-send"));
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
