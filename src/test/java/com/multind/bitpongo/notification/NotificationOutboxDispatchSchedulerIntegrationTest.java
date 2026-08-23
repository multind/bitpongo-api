package com.multind.bitpongo.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=outbox-scheduler-test-secret",
        "zhitoubao.notifications.bark.admin-push-url=",
        "zhitoubao.notifications.bark.allowed-hosts=localhost",
        "zhitoubao.notifications.bark.allow-private-hosts=true",
        "zhitoubao.notifications.bark.credential-encryption-key="
                + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "zhitoubao.notifications.bark.dispatch-delay=24h",
        "zhitoubao.notifications.bark.dispatch-initial-delay=24h",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationOutboxDispatchSchedulerIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BarkProperties properties;

    @Test
    void productionDefaultsEnableOnlyTheScheduledWrapperAroundAvailableCoreBeans() {
        assertThat(properties.dispatchEnabled()).isTrue();
        assertThat(context.getBeansOfType(NotificationOutboxDispatchScheduler.class))
                .hasSize(1);
        assertThat(context.getBean(NotificationOutboxDispatcher.class)).isNotNull();
        assertThat(context.getBean(NotificationOutboxLeaseService.class)).isNotNull();
        assertThat(context.getBean(NotificationOutboxDeliveryStore.class)).isNotNull();
    }
}
