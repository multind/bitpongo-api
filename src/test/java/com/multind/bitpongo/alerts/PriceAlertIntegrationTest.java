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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
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
}
