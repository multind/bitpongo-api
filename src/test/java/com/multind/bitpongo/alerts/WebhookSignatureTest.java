package com.multind.bitpongo.alerts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebhookSignatureTest {
    @Test void acceptsCorrectHmacAndRejectsModifiedBodyAndStaleTimestamp() throws Exception {
        var key="test-key-never-a-production-secret";
        var body="{\"message\":\"BTC crossing\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp="1780000000",nonce="nonce-1234567890123456";
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=java.util.HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+nonce+"\n"+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
        Instant now=Instant.ofEpochSecond(1780000000);
        assertThatCode(()->WebhookSignature.verify(key,timestamp,nonce,signature,body,now)).doesNotThrowAnyException();
        assertThatThrownBy(()->WebhookSignature.verify(key,timestamp,nonce,signature,"changed".getBytes(StandardCharsets.UTF_8),now)).hasMessageContaining("签名");
        assertThatThrownBy(()->WebhookSignature.verify(key,timestamp,nonce,signature,body,now.plusSeconds(301))).hasMessageContaining("时间");
        assertThatThrownBy(()->WebhookSignature.verify(key,"-9223372036854775808",nonce,signature,body,now)).hasMessageContaining("时间");
        assertThatThrownBy(()->WebhookSignature.verify(key,timestamp,"short",signature,body,now)).hasMessageContaining("nonce");
    }
}
