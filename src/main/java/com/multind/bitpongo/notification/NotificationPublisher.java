package com.multind.bitpongo.notification;

public interface NotificationPublisher {

    void publish(NotificationEvent event);
}
