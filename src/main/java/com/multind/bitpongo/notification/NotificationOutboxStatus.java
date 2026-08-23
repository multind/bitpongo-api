package com.multind.bitpongo.notification;

public enum NotificationOutboxStatus {
    PENDING,
    SENDING,
    SENT,
    DEAD,
    SKIPPED
}
