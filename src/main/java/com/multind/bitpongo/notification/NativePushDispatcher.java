package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Profile("!test")
@Service
public class NativePushDispatcher {
    private final JdbcTemplate jdbc;
    private final NativePushClient client;
    private final NativePushOutbox outbox;
    private final TransactionTemplate tx;
    private final int staleDays;
    private final int retentionDays;
    public NativePushDispatcher(JdbcTemplate jdbc,NativePushClient client,NativePushOutbox outbox,PlatformTransactionManager manager,
            @Value("${FCM_STALE_DEVICE_DAYS:90}") int staleDays,@Value("${FCM_DELIVERY_RETENTION_DAYS:30}") int retentionDays) {
        this.jdbc=jdbc;this.client=client;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
        this.staleDays=Math.max(1,staleDays);this.retentionDays=Math.max(1,retentionDays);
    }
    @Scheduled(fixedDelayString="${FCM_DISPATCH_DELAY:5s}",initialDelayString="${FCM_DISPATCH_INITIAL_DELAY:30s}")
    public void dispatch() {
        if(!client.available()) return;
        for(int i=0;i<50;i++) {
            // Large batches may outlive an OAuth token; refresh before every claim, outside database locks.
            if(!client.prepare()) return;
            Lease lease=claim(NativePushService.now());
            if(lease==null) return;
            try { deliver(lease); }
            catch (RuntimeException failure) {
                // Poison records and transaction failures must not monopolize the oldest eligible lease.
                tx.executeWithoutResult(status -> jdbc.update("""
                        update push_delivery set status=case when attempts>=7 then 'DEAD' else 'RETRY' end,
                        attempts=attempts+1,next_attempt_at=?,lease_token=null,lease_until=null,last_error='DISPATCH_ERROR',updated_at=?
                        where id=? and status='SENDING' and lease_token=?
                        """,NativePushService.now().plusMinutes(1),NativePushService.now(),lease.id(),lease.token()));
            }
        }
    }
    record Lease(long id,long userId,String installationId,String token) {}
    Lease claim(LocalDateTime now) {
        return tx.execute(status->{
            var rows=jdbc.queryForList("""
                    select id,user_id,installation_id from push_delivery where
                    ((status in ('PENDING','RETRY') and next_attempt_at<=?) or (status='SENDING' and lease_until<=?))
                    order by next_attempt_at,id limit 1 for update skip locked
                    """,now,now);
            if(rows.isEmpty())return null;
            var row=rows.getFirst();String token=UUID.randomUUID().toString();long id=((Number)row.get("id")).longValue();
            jdbc.update("update push_delivery set status='SENDING',lease_token=?,lease_until=?,updated_at=? where id=?",token,now.plusMinutes(2),now,id);
            return new Lease(id,((Number)row.get("user_id")).longValue(),(String)row.get("installation_id"),token);
        });
    }
    void deliver(Lease lease) {
        tx.executeWithoutResult(status->{
            // Hold account/device locks through provider acceptance: rebind/logout cannot race the final ownership check.
            var users=jdbc.queryForList("select status from user where id=? for update",String.class,lease.userId());
            var devices=jdbc.queryForList("select * from push_device where installation_id=? for update",lease.installationId());
            var jobs=jdbc.queryForList("select * from push_delivery where id=? and status='SENDING' and lease_token=? and lease_until>? for update",lease.id(),lease.token(),NativePushService.now());
            if(jobs.isEmpty())return;
            var job=jobs.getFirst();
            var settings=jdbc.queryForList("select * from user_push_setting where user_id=?",lease.userId());
            LocalDateTime now=NativePushService.now();
            LocalDateTime expiry=date(job.get("expires_at"));
            String type=(String)job.get("event_type");
            if(users.isEmpty()||!"active".equals(users.getFirst())||devices.isEmpty()||settings.isEmpty()
                    ||((Number)devices.getFirst().get("user_id")).longValue()!=lease.userId()
                    ||!job.get("binding_version").equals(devices.getFirst().get("binding_version"))
                    ||!NativePushOutbox.bool(devices.getFirst().get("enabled"))||!NativePushOutbox.bool(settings.getFirst().get("enabled"))
                    ||!expiry.isAfter(now)||(!"PUSH_TEST".equals(type)&&!NativePushOutbox.allowed(settings.getFirst(),NotificationEventType.valueOf(type)))) {
                finish(lease,"SKIPPED",0,now,"INELIGIBLE");return;
            }
            int attempts=((Number)job.get("attempts")).intValue()+1;
            if(attempts>8){finish(lease,"DEAD",attempts-1,now,"ATTEMPTS_EXHAUSTED");return;}
            var message=outbox.decode((String)job.get("message"));
            NativePushOutbox.configureDelivery(message,type,NativePushOutbox.bool(settings.getFirst().get("sound_enabled")),expiry,now);
            NativePushClient.Result result=client.send((String)devices.getFirst().get("token"),message);
            now=NativePushService.now();
            if(result.invalidToken()) jdbc.update("update push_device set enabled=false where installation_id=? and user_id=? and binding_version=?",lease.installationId(),lease.userId(),job.get("binding_version"));
            if(result.accepted())finish(lease,"ACCEPTED",attempts,now,null);
            else if(result.retryable()&&attempts<8&&expiry.isAfter(now)) {
                long delay=Math.max(result.retrySeconds(),Math.min(3600,60L<<(attempts-1)));
                finish(lease,"RETRY",attempts,now.plusSeconds(delay),result.code());
            } else finish(lease,"DEAD",attempts,now,result.code());
        });
    }
    private void finish(Lease lease,String state,int attempts,LocalDateTime next,String error) {
        jdbc.update("update push_delivery set status=?,attempts=?,next_attempt_at=?,lease_until=null,lease_token=null,last_error=?,updated_at=? where id=? and status='SENDING' and lease_token=?",
                state,attempts,next,error,NativePushService.now(),lease.id(),lease.token());
    }
    @Scheduled(fixedDelayString="${FCM_CLEANUP_DELAY:24h}",initialDelayString="${FCM_CLEANUP_INITIAL_DELAY:5m}")
    public void cleanup() { cleanup(NativePushService.now()); }
    void cleanup(LocalDateTime now) {
        tx.executeWithoutResult(status->{
            // Retain explicit opt-out across arbitrarily long offline periods, without retaining a live token.
            jdbc.update("update push_device set token='',token_hash=sha2(concat('revoked:',installation_id,':',uuid()),256) where revoked=true and token<>''");
            jdbc.update("delete from push_device where last_seen_at<? and revoked=false",now.minusDays(staleDays));
            jdbc.update("update push_delivery set status='EXPIRED',lease_token=null,lease_until=null,updated_at=? where expires_at<=? and (status in ('PENDING','RETRY') or (status='SENDING' and lease_until<=?))",now,now,now);
            jdbc.update("delete from push_delivery where updated_at<? and status in ('ACCEPTED','SKIPPED','DEAD','EXPIRED')",now.minusDays(retentionDays));
        });
    }
    private static LocalDateTime date(Object value) {return value instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(LocalDateTime)value;}
}
