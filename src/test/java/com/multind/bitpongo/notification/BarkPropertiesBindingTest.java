package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.AccountDeletionService;
import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.plan.OrderRepository;
import com.multind.bitpongo.plan.PlanApplicationService;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.plan.SnapshotRepository;
import com.multind.bitpongo.scheduler.OrderIntentRepository;
import com.multind.bitpongo.strategy.CoinRepository;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import com.multind.bitpongo.strategy.StrategyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "zhitoubao.notifications.bark.user-notifications-enabled=false",
        "zhitoubao.notifications.bark.admin-push-url=redacted-admin-target",
        "zhitoubao.notifications.bark.allowed-hosts=one.example.test:8443,two.example.test",
        "zhitoubao.notifications.bark.allow-private-hosts=true",
        "zhitoubao.notifications.bark.credential-encryption-key=redacted-test-encryption-key",
        "zhitoubao.notifications.bark.notify-on-startup=true",
        "zhitoubao.notifications.bark.app-public-url=redacted-public-origin"
})
@ActiveProfiles("test")
@MockitoBean(types = {
        PlanRepository.class,
        SnapshotRepository.class,
        StrategyRepository.class,
        CoinRepository.class,
        OrderRepository.class,
        OrderIntentRepository.class,
        JdbcTemplate.class,
        UserRepository.class,
        AccountDeletionService.class,
        ExchangeRepository.class,
        PlanApplicationService.class,
        StrategyApplicationService.class
})
class BarkPropertiesBindingTest {

    @Autowired
    private BarkProperties properties;

    @Test
    void bindsAllBarkSettingsIncludingCommaSeparatedAllowedHosts() {
        assertThat(properties.userNotificationsEnabled()).isFalse();
        assertThat(properties.adminPushUrl()).isEqualTo("redacted-admin-target");
        assertThat(properties.allowedHosts())
                .isEqualTo(Set.of("one.example.test:8443", "two.example.test"));
        assertThat(properties.allowPrivateHosts()).isTrue();
        assertThat(properties.credentialEncryptionKey()).isEqualTo("redacted-test-encryption-key");
        assertThat(properties.notifyOnStartup()).isTrue();
        assertThat(properties.appPublicUrl()).isEqualTo("redacted-public-origin");
    }
}
