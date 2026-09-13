package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties={"zhitoubao.jwt.secret-key=native-push-test-secret",
        "zhitoubao.notifications.bark.dispatch-enabled=false","zhitoubao.notifications.bark.user-notifications-enabled=false",
        "zhitoubao.market.stream-enabled=false","spring.quartz.auto-startup=false",
        "FCM_DISPATCH_INITIAL_DELAY=24h","FCM_CLEANUP_INITIAL_DELAY=24h"})
class NativePushReliabilityIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer mysql=new MySQLContainer(System.getProperty("test.mysql.image","mysql:9.7.0"));
    @Autowired JdbcTemplate jdbc;
    @Autowired NativePushService service;
    @Autowired NativePushOutbox outbox;
    @Autowired NativePushDispatcher dispatcher;
    @Autowired NotificationOutboxEnqueuer events;
    @MockitoBean NativePushClient client;
    long user;
    long other;
    String a;
    String b;
    @BeforeEach void setup() {
        jdbc.update("delete from push_delivery");jdbc.update("delete from push_device");jdbc.update("delete from user_push_setting");
        user=account();other=account();a=UUID.randomUUID().toString();b=UUID.randomUUID().toString();
        when(client.available()).thenReturn(true);
        when(client.prepare()).thenReturn(true);
        when(client.send(anyString(),anyMap())).thenReturn(new NativePushClient.Result(true,false,false,0,"ACCEPTED"));
    }
    long account() {
        jdbc.update("insert into user(name,email,password) values('push',?,'unused')",UUID.randomUUID()+"@example.com");
        return jdbc.queryForObject("select last_insert_id()",Long.class);
    }
    void register(long owner,String id,String token) {
        service.register(owner,id,new NativePushController.Registration(token,"android","authorized","1.0","en-US","UTC"));
    }
    void enqueue() {
        events.enqueue(new NotificationEvent(NotificationEventType.TRADE_FAILED,user,123L,null,java.time.Instant.now(),
                "native-reliability:"+UUID.randomUUID(),Map.of("error","secret_key=not-for-push")));
    }
    @Test void barkDisabledDoesNotFilterNativeAndSiblingSuccessIsNotRetried() {
        register(user,a,"token-a");register(user,b,"token-b");enqueue();
        when(client.send(eq("token-b"),anyMap())).thenReturn(new NativePushClient.Result(false,true,false,60,"UNAVAILABLE"));
        dispatcher.dispatch();
        assertThat(jdbc.queryForList("select status from push_delivery order by installation_id",String.class)).containsExactlyInAnyOrder("ACCEPTED","RETRY");
        jdbc.update("update push_delivery set next_attempt_at=? where status='RETRY'",LocalDateTime.now().minusDays(1));
        when(client.send(eq("token-b"),anyMap())).thenReturn(new NativePushClient.Result(true,false,false,0,"ACCEPTED"));
        dispatcher.dispatch();
        verify(client,times(1)).send(eq("token-a"),anyMap());
        verify(client,times(2)).send(eq("token-b"),anyMap());
    }
    @Test void rebindAndCrossAccountDeleteCannotDeliverOldAccountData() {
        register(user,a,"token-a");enqueue();
        var lease=dispatcher.claim(LocalDateTime.now().plusMinutes(1));
        register(other,a,"token-new");service.remove(user,a);dispatcher.deliver(lease);
        assertThat(service.devices(user)).isEmpty();assertThat(service.devices(other)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isZero();
        verify(client,never()).send(anyString(),anyMap());
    }
    @Test void tokenRotationCancelsPendingButIdempotentRefreshDoesNot() {
        register(user,a,"token-a");enqueue();register(user,a,"token-a");
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isEqualTo(1);
        register(user,a,"rotated");
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isZero();
    }
    @Test void revokedInstallationStaysDisabledUntilExplicitActivation() {
        register(user,a,"token-a");service.remove(user,a);
        assertThat(service.register(user,a,new NativePushController.Registration("token-a","android","authorized","1","en-us","UTC"))).isFalse();
        assertThat(service.devices(user).getFirst().enabled()).isFalse();
        assertThat(service.register(user,a,new NativePushController.Registration("token-a","android","authorized","1","en-us","UTC",true))).isTrue();
        assertThat(service.devices(user).getFirst().enabled()).isTrue();
    }
    @Test void testThrottleIsAccountBoundAndUnknownDeviceRejected() {
        register(user,a,"token-a");register(user,b,"token-b");service.test(user,a);
        assertThatThrownBy(()->service.test(user,b)).hasMessageContaining("一分钟");
        assertThatThrownBy(()->service.test(other,a)).hasMessageContaining("未注册");
    }
    @Test void invalidArgumentKeepsDeviceButUnregisteredDisablesCurrentBinding() {
        register(user,a,"token-a");enqueue();
        when(client.send(anyString(),anyMap())).thenReturn(FcmPushClient.classify(400,"INVALID_ARGUMENT",0));
        dispatcher.dispatch();assertThat(service.devices(user).getFirst().enabled()).isTrue();
        enqueue();when(client.send(anyString(),anyMap())).thenReturn(FcmPushClient.classify(404,"UNREGISTERED",0));
        dispatcher.dispatch();assertThat(service.devices(user).getFirst().enabled()).isFalse();
    }
    @Test void concurrentWorkersDoNotClaimSameDeviceDelivery() throws Exception {
        register(user,a,"token-a");enqueue();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->dispatcher.claim(LocalDateTime.now().plusMinutes(1)));
            var second=pool.submit(()->dispatcher.claim(LocalDateTime.now().plusMinutes(1)));
            assertThat(java.util.stream.Stream.of(first.get(),second.get()).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        }
    }
    @Test void preferencesAndStaleDeviceCleanupPreventDeliveryAndRemovePending() {
        register(user,a,"token-a");enqueue();
        service.save(user,new NativePushController.Settings(true,true,false,true,true,false,"en-US","UTC"));
        dispatcher.dispatch();verify(client,never()).send(anyString(),anyMap());
        jdbc.update("update push_device set last_seen_at=?",LocalDateTime.now().minusDays(91));
        dispatcher.cleanup(LocalDateTime.now());assertThat(service.devices(user)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isZero();
    }
    @Test void softDeletionCleanupCannotBeResurrected() {
        register(user,a,"token-a");enqueue();service.deleteForUser(user);
        jdbc.update("update user set status='deleted' where id=?",user);
        assertThatThrownBy(()->register(user,a,"late-token")).hasMessageContaining("账号不可用");
        assertThat(jdbc.queryForObject("select count(*) from user_push_setting where user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from push_delivery where user_id=?",Integer.class,user)).isZero();
    }
    @Test void poisonRecordDoesNotStarveHealthySiblingAndExpiresAreRetainedThenCleaned() {
        register(user,a,"token-a");register(user,b,"token-b");enqueue();
        jdbc.update("update push_delivery set event_type='UNKNOWN' where installation_id=?",a);
        dispatcher.dispatch();
        assertThat(jdbc.queryForList("select status from push_delivery",String.class)).containsExactlyInAnyOrder("RETRY","ACCEPTED");
        jdbc.update("update push_delivery set expires_at=? where status='RETRY'",LocalDateTime.now().minusDays(1));
        dispatcher.cleanup(LocalDateTime.now());
        assertThat(jdbc.queryForList("select status from push_delivery",String.class)).contains("EXPIRED");
        jdbc.update("update push_delivery set updated_at=?",LocalDateTime.now().minusDays(31));
        dispatcher.cleanup(LocalDateTime.now());
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isZero();
    }
    @Test void expiredLeaseIsReclaimedAndOldOwnerCannotDeliverOrCompleteIt() {
        register(user,a,"token-a");enqueue();
        var first=dispatcher.claim(NativePushService.now());
        jdbc.update("update push_delivery set lease_until=?",NativePushService.now().minusMinutes(1));
        var second=dispatcher.claim(NativePushService.now());
        dispatcher.deliver(first);verify(client,never()).send(anyString(),anyMap());
        dispatcher.deliver(second);verify(client,times(1)).send(eq("token-a"),anyMap());
        assertThat(jdbc.queryForObject("select status from push_delivery",String.class)).isEqualTo("ACCEPTED");
    }
    @Test void duplicatePublishedEventFansOutOnlyOncePerDeviceAndSanitizesSecrets() {
        register(user,a,"token-a");
        var event=new NotificationEvent(NotificationEventType.TRADE_FAILED,user,123L,null,java.time.Instant.now(),
                "native-dedupe:"+UUID.randomUUID(),Map.of("error","secret_key=not-for-push"));
        events.enqueue(event);events.enqueue(event);
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select message from push_delivery",String.class)).doesNotContain("not-for-push").contains("redacted");
    }
    @Test void credentialPreparationFailureLeavesUnclaimedWorkForNextPoll() {
        register(user,a,"token-a");register(user,b,"token-b");enqueue();
        when(client.prepare()).thenReturn(true,false);
        dispatcher.dispatch();
        assertThat(jdbc.queryForList("select status from push_delivery",String.class)).containsExactlyInAnyOrder("ACCEPTED","PENDING");
    }
    @Test void deviceLanguageAndTimezoneControlMessagesWithoutAnAccountSettingsSave() {
        register(user,a,"token-a");
        service.register(user,b,new NativePushController.Registration("token-b","ios","authorized","1","zh-tw","Asia/Taipei"));
        enqueue();
        assertThat(jdbc.queryForObject("select message from push_delivery where installation_id=?",String.class,a)).contains("Trade failed","UTC");
        assertThat(jdbc.queryForObject("select message from push_delivery where installation_id=?",String.class,b)).contains("交易失敗","Asia/Taipei");
        jdbc.update("delete from push_delivery");
        service.register(user,a,new NativePushController.Registration("token-a","android","authorized","1","en-us","America/New_York"));
        enqueue();
        assertThat(jdbc.queryForObject("select message from push_delivery where installation_id=?",String.class,a)).contains("America/New_York");
        jdbc.update("delete from push_delivery");service.test(user,a);
        assertThat(jdbc.queryForObject("select message from push_delivery",String.class)).contains("Notification test");
    }
    @Test void logoutDetachesLiveBindingAndSameAccountCanRegisterAgain() {
        register(user,a,"token-a");enqueue();service.logout(user,a,"token-a");
        assertThat(service.devices(user)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from push_delivery",Integer.class)).isZero();
        assertThat(service.register(user,a,new NativePushController.Registration("token-new","android","authorized","1","en-US","UTC"))).isTrue();
        assertThat(service.devices(user).getFirst().enabled()).isTrue();
    }
    @Test void staleLogoutTokenOrDifferentOwnerCannotRemoveCurrentRegistration() {
        register(user,a,"token-a");register(user,a,"token-rotated");
        service.logout(user,a,"token-a");service.logout(other,a,"token-rotated");
        assertThat(service.devices(user)).hasSize(1);
        assertThat(service.devices(user).getFirst().enabled()).isTrue();
        service.logout(user,a,"token-rotated");assertThat(service.devices(user)).isEmpty();
    }
    @Test void logoutCleanupPreservesExplicitRevocationTombstone() {
        register(user,a,"token-a");service.remove(user,a);service.logout(user,a,"token-a");
        assertThat(service.devices(user)).hasSize(1);
        assertThat(service.register(user,a,new NativePushController.Registration("token-new","android","authorized","1","en-US","UTC"))).isFalse();
        assertThat(service.devices(user).getFirst().enabled()).isFalse();
    }
    @Test void staleRevokedDeviceRemainsRevokedAfterCleanupUntilExplicitActivation() {
        register(user,a,"token-a");service.remove(user,a);
        jdbc.update("update push_device set last_seen_at=? where installation_id=?",NativePushService.now().minusDays(91),a);
        dispatcher.cleanup(NativePushService.now());
        assertThat(service.register(user,a,new NativePushController.Registration("token-new","android","authorized","1","en-US","UTC"))).isFalse();
        assertThat(service.devices(user).getFirst().enabled()).isFalse();
        assertThat(service.register(user,a,new NativePushController.Registration("token-new","android","authorized","1","en-US","UTC",true))).isTrue();
        assertThat(service.devices(user).getFirst().enabled()).isTrue();
    }
    @Test void tokenTransferCannotEraseAnotherInstallationsRevocation() {
        register(user,a,"token-a");service.remove(user,a);
        assertThat(service.register(user,a,new NativePushController.Registration("shared-token","android","authorized","1","en-US","UTC"))).isFalse();
        assertThat(jdbc.queryForObject("select token from push_device where installation_id=?",String.class,a)).isEmpty();
        register(user,b,"shared-token");
        assertThat(service.register(user,a,new NativePushController.Registration("shared-token","android","authorized","1","en-US","UTC"))).isFalse();
        assertThat(service.devices(user)).hasSize(2);
        assertThat(service.devices(user).stream().filter(d->d.installationId().equals(b)).findFirst().orElseThrow().enabled()).isTrue();
        assertThat(service.register(user,a,new NativePushController.Registration("token-returned","android","authorized","1","en-US","UTC"))).isFalse();
        assertThat(service.devices(user).stream().filter(d->d.installationId().equals(a)).findFirst().orElseThrow().enabled()).isFalse();
        service.deleteForUser(user);
        assertThat(jdbc.queryForObject("select count(*) from push_device where user_id=?",Integer.class,user)).isZero();
    }
    @Test void legacyUnscrubbedTombstoneCannotBeReactivatedThroughUniqueTokenConflict() {
        register(user,a,"legacy-token");service.remove(user,a);
        jdbc.update("update push_device set token=?,token_hash=? where installation_id=?","legacy-token",NativePushService.hash("legacy-token"),a);
        register(user,b,"legacy-token");
        assertThat(service.devices(user)).hasSize(2);
        assertThat(service.devices(user).stream().filter(d->d.installationId().equals(a)).findFirst().orElseThrow().enabled()).isFalse();
        assertThat(jdbc.queryForObject("select token from push_device where installation_id=?",String.class,a)).isEmpty();
    }
}
