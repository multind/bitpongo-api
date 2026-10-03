package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.MarketDataService;
import com.multind.bitpongo.market.MarketDtos;
import com.multind.bitpongo.notification.NativePushClient;
import com.multind.bitpongo.notification.NativePushController;
import com.multind.bitpongo.notification.NativePushService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"zhitoubao.jwt.secret-key=price-alert-test-secret",
        "zhitoubao.notifications.bark.dispatch-enabled=false", "zhitoubao.notifications.bark.user-notifications-enabled=false",
        "zhitoubao.market.stream-enabled=false", "spring.quartz.auto-startup=false",
        "PRICE_ALERT_POLL_DELAY=24h", "FCM_DISPATCH_INITIAL_DELAY=24h", "FCM_CLEANUP_INITIAL_DELAY=24h"})
class PriceAlertIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer mysql = new MySQLContainer(System.getProperty("test.mysql.image", "mysql:9.7.0"));
    @Autowired JdbcTemplate jdbc;
    @Autowired PriceAlertService alerts;
    @Autowired AlertEventService inbox;
    @Autowired NativePushService devices;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.multind.bitpongo.auth.AccountDeletionService accounts;
    @Autowired com.multind.bitpongo.auth.PasswordCompatibilityService passwords;
    @MockitoBean MarketDataService market;
    @MockitoBean NativePushClient push;
    long user;
    @BeforeEach void setup() {
        when(market.symbols(anyString())).thenReturn(List.of(new MarketDtos.Symbol("BTCUSDT", "BTC", "USDT")));
        when(push.available()).thenReturn(true);
        jdbc.update("insert into user(name,email,password) values('alerts',?,'unused')", UUID.randomUUID()+"@example.com");
        user = jdbc.queryForObject("select last_insert_id()", Long.class);
        devices.register(user, UUID.randomUUID().toString(), new NativePushController.Registration("token-"+UUID.randomUUID(), "ios", "authorized", "1", "en-US", "UTC"));
    }
    long rule(String frequency) {
        return alerts.create(user, new AlertDtos.Create("Bitcoin alert", "BTCUSDT", "ABOVE", new BigDecimal("100"), null, null, frequency, "1h", null));
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where user_id=?", Integer.class, user); }
    @Test void editingPreservesPauseAndHistoryButResetsSampleBaseline() {
        long id=rule("EVERY_TIME");
        alerts.sample(user,id,new BigDecimal("110"),Instant.now().plusSeconds(1),1);
        alerts.enabled(user,id,false);
        alerts.update(user,id,new AlertDtos.Create("Updated alert","BTCUSDT","CROSS_UP",new BigDecimal("200"),null,null,"ONCE","1h",null));
        var saved=alerts.detail(user,id);
        assertThat(saved.name()).isEqualTo("Updated alert");
        assertThat(saved.target()).isEqualByComparingTo("200");
        assertThat(saved.enabled()).isFalse();
        assertThat(saved.triggeredCount()).isEqualTo(1);
        assertThat(count("alert_event")).isEqualTo(1);
        assertThatThrownBy(()->alerts.update(user+1,id,new AlertDtos.Create("stolen","BTCUSDT","ABOVE",BigDecimal.ONE,null,null,"ONCE","1h",null))).hasMessageContaining("不存在");
    }
    @Test void expiredRulesAreNotListedAsActiveOrAllowedToResume() {
        long id=rule("ONCE");
        jdbc.update("update price_alert set expires_at=? where id=?",PriceAlertService.utc(Instant.now().minusSeconds(1)),id);
        assertThat(alerts.list(user,"","active",0,20).items()).isEmpty();
        assertThat(alerts.list(user,"","inactive",0,20).items()).hasSize(1);
        assertThatThrownBy(()->alerts.enabled(user,id,true)).hasMessageContaining("过期");
        alerts.sample(user,id,new BigDecimal("110"),Instant.now(),1);
        assertThat(count("alert_event")).isZero();
    }
    @Test void concurrentDuplicateSamplesProduceOneEventAndOnePush() throws Exception {
        long id = rule("EVERY_TIME"); Instant at = Instant.now().plusSeconds(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> alerts.sample(user, id, new BigDecimal("110"), at, 1));
            var b = pool.submit(() -> alerts.sample(user, id, new BigDecimal("110"), at, 1));
            a.get(); b.get();
        }
        assertThat(alerts.detail(user, id).triggeredCount()).isEqualTo(1);
        assertThat(count("alert_event")).isEqualTo(1);
        assertThat(count("push_delivery")).isEqualTo(1);
    }
    @Test void onceStopsAndPausedRuleDoesNotTrigger() {
        long id = rule("ONCE"); Instant at = Instant.now().plusSeconds(1);
        alerts.sample(user,id,new BigDecimal("110"),at,1);
        alerts.sample(user,id,new BigDecimal("120"),at.plusSeconds(1),1);
        assertThat(alerts.detail(user,id).enabled()).isFalse();
        assertThat(count("alert_event")).isEqualTo(1);
        long paused=rule("EVERY_TIME");alerts.enabled(user,paused,false);
        alerts.sample(user,paused,new BigDecimal("120"),at.plusSeconds(2),1);
        assertThat(count("alert_event")).isEqualTo(1);
    }
    @Test void historicalCandleBeforeCreationDoesNotNotify() {
        long id=rule("CANDLE_CLOSE");
        alerts.sample(user,id,new BigDecimal("110"),Instant.now().minusSeconds(3600),1);
        assertThat(count("alert_event")).isZero();
    }
    @Test void anotherAccountCannotReadChangeOrDeleteRule() {
        long id=rule("ONCE");
        assertThatThrownBy(()->alerts.detail(user+1,id)).hasMessageContaining("不存在");
        assertThatThrownBy(()->alerts.enabled(user+1,id,false)).hasMessageContaining("不存在");
        assertThatThrownBy(()->alerts.delete(user+1,id)).hasMessageContaining("不存在");
        assertThat(alerts.detail(user,id).enabled()).isTrue();
    }
    @Test void unreadStateAndRecordSurviveRuleDeletion() {
        long id=rule("ONCE");alerts.sample(user,id,new BigDecimal("110"),Instant.now().plusSeconds(1),1);
        var event=inbox.list(user,false,0,20).items().getFirst();
        inbox.read(user,event.id());
        assertThat(inbox.unread(user)).isZero();
        alerts.delete(user,id);
        assertThat(inbox.list(user,false,0,20).items().getFirst().ruleId()).isNull();
    }
    @Test void triggerAndPushRollbackTogether() {
        long id=rule("ONCE");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            alerts.sample(user,id,new BigDecimal("110"),Instant.now().plusSeconds(1),1);
            tx.setRollbackOnly();
        });
        assertThat(count("alert_event")).isZero();
        assertThat(count("push_delivery")).isZero();
        assertThat(alerts.detail(user,id).triggeredCount()).isZero();
        assertThat(alerts.detail(user,id).enabled()).isTrue();
    }
    @Test void minuteCooldownAllowsOnlyOneNotificationPerBucket() {
        long id=rule("PER_MINUTE");
        Instant at=Instant.ofEpochSecond((Instant.now().getEpochSecond()/60+1)*60);
        alerts.sample(user,id,new BigDecimal("110"),at,0);
        alerts.sample(user,id,new BigDecimal("120"),at.plusSeconds(1),0);
        alerts.sample(user,id,new BigDecimal("120"),at.plusSeconds(60),0);
        assertThat(alerts.detail(user,id).triggeredCount()).isEqualTo(2);
        assertThat(count("push_delivery")).isEqualTo(2);
    }
    @Test void inboxAndLogsCanBeDeletedIndependentlyWithUserIsolation() {
        long id=rule("ONCE");alerts.sample(user,id,new BigDecimal("110"),Instant.now().plusSeconds(1),1);
        long eventId=inbox.list(user,false,0,20).items().getFirst().id();
        inbox.deleteLog(user+1,eventId);
        assertThat(inbox.logs(user,0,20).items()).hasSize(1);
        inbox.clear(user);
        assertThat(inbox.list(user,false,0,20).items()).isEmpty();
        assertThat(inbox.unread(user)).isZero();
        assertThat(inbox.logs(user,0,20).items()).hasSize(1);
        inbox.deleteLog(user,eventId);
        assertThat(inbox.logs(user,0,20).items()).isEmpty();
    }
    @Test void clearingLogsLeavesInboxAndUnreadIntact() {
        long id=rule("ONCE");alerts.sample(user,id,new BigDecimal("110"),Instant.now().plusSeconds(1),1);
        inbox.clearLogs(user);
        assertThat(inbox.logs(user,0,20).items()).isEmpty();
        assertThat(inbox.list(user,false,0,20).items()).hasSize(1);
        assertThat(inbox.unread(user)).isEqualTo(1);
    }
    @Test void accountDeletionRemovesAlertDataAndWebhookCredentials() {
        long id=rule("ONCE");alerts.sample(user,id,new BigDecimal("110"),Instant.now().plusSeconds(1),1);
        jdbc.update("update user set password=? where id=?",passwords.hash("Delete-me-123"),user);
        jdbc.update("insert into webhook_rule(user_id,name,description,keywords,allowed_ips,public_id,created_at,updated_at) values(?,'private','','','',?,?,?)",user,UUID.randomUUID().toString(),PriceAlertService.utc(Instant.now()),PriceAlertService.utc(Instant.now()));
        accounts.delete(user,"Delete-me-123");
        assertThat(count("price_alert")).isZero();assertThat(count("alert_event")).isZero();assertThat(count("webhook_rule")).isZero();
    }
}
