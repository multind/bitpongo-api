package com.multind.bitpongo.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class BarkNotificationSender {

    private final BarkClient bark;

    BarkNotificationSender(BarkClient bark) {
        this.bark = bark;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void send(BarkTarget target, BarkMessage message) {
        bark.send(target, message);
    }
}
