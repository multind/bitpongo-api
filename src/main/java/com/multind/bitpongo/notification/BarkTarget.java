package com.multind.bitpongo.notification;

import java.net.URI;

public record BarkTarget(URI serverUrl, String deviceKey) {

    @Override
    public String toString() {
        return "BarkTarget[serverUrl=" + serverUrl + ", deviceKey=<redacted>]";
    }
}
