package com.multind.bitpongo.notification;

public interface BarkClient {

    void send(BarkTarget target, BarkMessage message);
}
