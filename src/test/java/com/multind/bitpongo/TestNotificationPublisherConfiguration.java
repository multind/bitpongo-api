package com.multind.bitpongo;

import com.multind.bitpongo.notification.NotificationPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestNotificationPublisherConfiguration {

    @Bean
    NotificationPublisher testNotificationPublisher() {
        return event -> {
            // Contract-test contexts do not persist business notification events.
        };
    }
}
