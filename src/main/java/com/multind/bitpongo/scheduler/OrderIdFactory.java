package com.multind.bitpongo.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class OrderIdFactory {
    public String create(long planId, String symbol, Instant scheduledFireTime) {
        String material = planId + "|" + symbol + "|" + scheduledFireTime;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "ztb_" + planId + "_" + HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256不可用", impossible);
        }
    }
}
