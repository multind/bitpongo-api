package com.multind.bitpongo.notification;

public record BarkMessage(
        String title,
        String body,
        String level,
        Integer volume,
        boolean call,
        String sound,
        String group,
        String url) {
}
