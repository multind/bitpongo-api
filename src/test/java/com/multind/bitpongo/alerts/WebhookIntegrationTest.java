package com.multind.bitpongo.alerts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties="WEBHOOK_CREDENTIAL_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class WebhookIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired WebhookService hooks;
    @Test void signedDeliveryIsAtomicOwnedAndRejectsReplayAndRevokedUrl() throws Exception {
        long id=hooks.create(user,new WebhookService.Draft("Signal","Description","BTC","127.0.0.1"));
        var activated=hooks.activate(user,id);
        byte[] body="{\"title\":\"BTC signal\",\"message\":\"BTC crossing\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        String signature=sign(activated.signingKey(),timestamp,nonce,body);
        hooks.receive(activated.publicId(),timestamp,nonce,signature,body,"127.0.0.1");
        assertThat(count("alert_event")).isEqualTo(1);
        assertThat(count("push_delivery")).isEqualTo(1);
        assertThatThrownBy(()->hooks.receive(activated.publicId(),timestamp,nonce,signature,body,"127.0.0.1")).hasMessageContaining("重复");
        assertThatThrownBy(()->hooks.activate(user+1,id)).hasMessageContaining("不存在");
        hooks.revoke(user,id);
        assertThatThrownBy(()->hooks.receive(activated.publicId(),timestamp,nonce,signature,body,"127.0.0.1")).hasMessageContaining("不可用");
        assertThat(count("push_delivery")).isEqualTo(1);
    }
    @Test void badSignatureAndIpDoNotStoreNotification() {
        long id=hooks.create(user,new WebhookService.Draft("Signal","","","127.0.0.1"));
        var active=hooks.activate(user,id);byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->hooks.receive(active.publicId(),Long.toString(Instant.now().getEpochSecond()),UUID.randomUUID().toString(),"0".repeat(64),body,"127.0.0.1")).hasMessageContaining("签名");
        assertThatThrownBy(()->hooks.receive(active.publicId(),"0",UUID.randomUUID().toString(),"0".repeat(64),body,"127.0.0.2")).hasMessageContaining("IP");
        assertThat(count("alert_event")).isZero();assertThat(count("push_delivery")).isZero();
    }
    @Test void filteredDeliveryStillCleansExpiredReceipts() throws Exception {
        long id=hooks.create(user,new WebhookService.Draft("Signal","","BTC",""));
        var active=hooks.activate(user,id);
        jdbc.update("insert into webhook_receipt(webhook_id,nonce,received_at) values(?,?,?)",id,UUID.randomUUID().toString(),PriceAlertService.utc(Instant.now().minusSeconds(700)));
        byte[] body="{\"message\":\"ETH only\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        hooks.receive(active.publicId(),timestamp,nonce,sign(active.signingKey(),timestamp,nonce,body),body,"127.0.0.1");
        assertThat(jdbc.queryForObject("select count(*) from webhook_receipt where webhook_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(count("alert_event")).isZero();
        assertThat(count("push_delivery")).isZero();
    }
    @Test void reusedNonceWithExistingEventIsRejectedAfterReceiptRetention() throws Exception {
        long id=hooks.create(user,new WebhookService.Draft("Signal","","",""));
        var active=hooks.activate(user,id);
        byte[] body="{\"message\":\"BTC signal\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        String signature=sign(active.signingKey(),timestamp,nonce,body);
        hooks.receive(active.publicId(),timestamp,nonce,signature,body,"127.0.0.1");
        jdbc.update("delete from webhook_receipt where webhook_id=?",id);
        assertThatThrownBy(()->hooks.receive(active.publicId(),timestamp,nonce,signature,body,"127.0.0.1"))
                .isInstanceOf(com.multind.bitpongo.common.api.BusinessException.class).hasMessageContaining("重复");
        assertThat(count("alert_event")).isEqualTo(1);
    }
    static String sign(String key,String timestamp,String nonce,byte[] body) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
