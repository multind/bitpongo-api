package com.multind.bitpongo.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class WebhookSignature {
    private WebhookSignature() {}
    static void verify(String key,String timestamp,String nonce,String signature,byte[] body,Instant now) {
        long seconds;
        try { seconds=Long.parseLong(timestamp); } catch(Exception e) { throw PriceAlertService.invalid("Webhook 时间无效"); }
        if (seconds<now.getEpochSecond()-300 || seconds>now.getEpochSecond()+300) throw PriceAlertService.invalid("Webhook 时间已过期");
        if (nonce==null || !nonce.matches("[A-Za-z0-9_-]{16,64}")) throw PriceAlertService.invalid("Webhook nonce 无效");
        if (signature==null || !signature.matches("[a-fA-F0-9]{64}")) throw PriceAlertService.invalid("Webhook 签名无效");
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature))) throw PriceAlertService.invalid("Webhook 签名无效");
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Webhook 签名校验不可用",e); }
    }
}
