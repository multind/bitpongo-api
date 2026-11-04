package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.common.time.UtcDateTimes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class AccountRecoveryService {
    private final JdbcTemplate jdbc;
    private final PasswordCompatibilityService passwords;
    private final AccountSecurityService security;
    private final EmailCodeSender mail;
    private final TransactionTemplate transaction;
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public AccountRecoveryService(JdbcTemplate jdbc,PasswordCompatibilityService passwords,AccountSecurityService security,
            EmailCodeSender mail,PlatformTransactionManager manager,@Value("${zhitoubao.jwt.secret-key}") String secret) {
        this.jdbc=jdbc; this.passwords=passwords; this.security=security; this.mail=mail;
        transaction=new TransactionTemplate(manager); key=secret.getBytes(StandardCharsets.UTF_8);
    }
    public boolean available() { return mail.available(); }
    private static final String REMINDER_EMAIL = "REMINDER_EMAIL";
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ReminderEmail(String accountEmail, String customEmail, boolean available) {}

    public ReminderEmail reminderEmail(long userId) {
        var rows=jdbc.queryForList("""
            select u.email,c.reminder_email from user u
            left join user_notification_channel c on c.user_id=u.id
            where u.id=? and u.status='active'
            """,userId);
        if(rows.isEmpty()) throw new BusinessException(401,"请先登录");
        var row=rows.getFirst();
        return new ReminderEmail((String)row.get("email"),(String)row.get("reminder_email"),available());
    }

    public void useDefaultReminderEmail(long userId) {
        transaction.executeWithoutResult(s->{
            if(active(userId)==null) throw invalid();
            jdbc.update("update user_notification_channel set reminder_email=null where user_id=?",userId);
            invalidateReminderChallenges(userId);
        });
    }

    private void invalidateReminderChallenges(long userId) {
        jdbc.update("update account_email_challenge set consumed=true where user_id=? and purpose=?",userId,REMINDER_EMAIL);
    }
    private record Delivery(String id,String email,String code,String purpose,boolean send) {}
    public String request(String purpose,String email,String oldPassword,Long userId,String remoteAddress) {
        if(!available()) throw new BusinessException(503,"邮件验证码服务暂不可用");
        boolean reminder=REMINDER_EMAIL.equals(purpose);
        if(!"RESET".equals(purpose) && !"EMAIL".equals(purpose) && !reminder) throw new BusinessException(400,"验证码用途无效");
        String target=email.trim().toLowerCase(Locale.ROOT);
        if(("EMAIL".equals(purpose) || reminder) && userId==null) throw new BusinessException(401,"请先登录");
        // Persist rate counters separately, including failed ownership/password checks.
        transaction.executeWithoutResult(s->{
            if("EMAIL".equals(purpose)) rate("password:"+userId,10,false);
            if(reminder) rate("reminder:"+userId,10,false);
            rate("ip:"+remoteAddress,20,false);
            rate("email:"+target,5,true);
        });
        Delivery delivery=transaction.execute(s->{
            Map<String,Object> account;
            if(reminder) {
                account=active(userId);
                if(account==null || !(account.get("email") instanceof String current) || current.isBlank())
                    throw new BusinessException(400,"请先绑定账号邮箱");
                invalidateReminderChallenges(userId);
            } else if("EMAIL".equals(purpose)) {
                account=active(userId);
                if(account==null || !passwords.matches(oldPassword,(String)account.get("password"))) throw new BusinessException(400,"当前密码错误");
                if(target.equals(account.get("email"))) throw new BusinessException(400,"新邮箱不能与当前邮箱相同");
                if(!jdbc.queryForList("select id from user where email=?",target).isEmpty()) throw new BusinessException(400,"此邮箱不可用于绑定");
            } else {
                var candidates=jdbc.queryForList("select id,password,email,security_version from user where email=? and status='active' for update",target);
                account=candidates.isEmpty()?null:candidates.getFirst();
            }
            Long owner=account==null?null:((Number)account.get("id")).longValue();
            long version=account==null?0:((Number)account.get("security_version")).longValue();
            String id=UUID.randomUUID().toString(), code=String.format(Locale.ROOT,"%08d",random.nextInt(100_000_000));
            jdbc.update("update account_email_challenge set consumed=true where email=? and purpose=? and (user_id=? or user_id is null)",target,purpose,owner);
            Instant now=Instant.now();
            jdbc.update("insert into account_email_challenge(id,user_id,purpose,email,code_hash,security_version,expires_at,created_at) values(?,?,?,?,?,?,?,?)",
                id,owner,purpose,target,digest("code:"+id+":"+code),version,UtcDateTimes.toDatabase(now.plusSeconds(600)),UtcDateTimes.toDatabase(now));
            return new Delivery(id,target,code,purpose,owner!=null);
        });
        if(delivery.send()) {
            try { mail.send(delivery.email(),delivery.code(),delivery.purpose()); }
            catch(RuntimeException failure) {
                // Same public response as an unknown reset account; never expose provider errors.
                transaction.executeWithoutResult(s->jdbc.update("update account_email_challenge set consumed=true where id=?",delivery.id()));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("account verification email delivery failed");
                if(reminder) throw new BusinessException(503,"验证码发送失败，请稍后重试");
            }
        }
        return delivery.id();
    }
    public void reset(String id,String code,String password) {
        AccountSecurityService.validatePassword(password);
        finish(id,code,"RESET",null,null,password);
    }
    public void email(long userId,String id,String code,String oldPassword) { finish(id,code,"EMAIL",userId,oldPassword,null); }
    public void verifyReminderEmail(long userId,String id,String code) { finish(id,code,REMINDER_EMAIL,userId,null,null); }
    public void passwordAttempt(long userId) {
        transaction.executeWithoutResult(s->rate("password:"+userId,10,false));
    }
    private void finish(String id,String code,String purpose,Long caller,String oldPassword,String newPassword) {
        if(id==null || !id.matches("[a-fA-F0-9-]{36}") || code==null || !code.matches("[0-9]{8}")) throw invalid();
        String result;
        try {
            result=transaction.execute(s->{
                var initial=jdbc.queryForList("select user_id from account_email_challenge where id=?",id);
                if(initial.isEmpty() || initial.getFirst().get("user_id")==null) return "invalid";
                long owner=((Number)initial.getFirst().get("user_id")).longValue();
                if(caller!=null && caller!=owner) return "invalid";
                var account=active(owner);
                var rows=jdbc.queryForList("select * from account_email_challenge where id=? for update",id);
                if(account==null || rows.isEmpty()) return "invalid";
                var row=rows.getFirst();
                if(!purpose.equals(row.get("purpose")) || Boolean.TRUE.equals(row.get("consumed")) || ((Number)row.get("attempts")).intValue()>=5 ||
                    !date(row.get("expires_at")).isAfter(UtcDateTimes.toDatabase(Instant.now())) ||
                    ((Number)row.get("security_version")).longValue()!=((Number)account.get("security_version")).longValue()) return "invalid";
                jdbc.update("update account_email_challenge set attempts=attempts+1 where id=?",id);
                if(!MessageDigest.isEqual(digest("code:"+id+":"+code).getBytes(StandardCharsets.US_ASCII),((String)row.get("code_hash")).getBytes(StandardCharsets.US_ASCII))) return "invalid";
                if(REMINDER_EMAIL.equals(purpose)) {
                    if(!(account.get("email") instanceof String current) || current.isBlank()) return "invalid";
                    jdbc.update("""
                        insert into user_notification_channel(user_id,reminder_email,updated_at)
                        values(?,?,UTC_TIMESTAMP(6))
                        on duplicate key update reminder_email=?,updated_at=UTC_TIMESTAMP(6)
                        """,owner,row.get("email"),row.get("email"));
                    invalidateReminderChallenges(owner);
                    return null;
                } else if("EMAIL".equals(purpose)) {
                    if(!passwords.matches(oldPassword,(String)account.get("password"))) return "invalid";
                    jdbc.update("update user set email=? where id=?",row.get("email"),owner);
                } else {
                    if(!row.get("email").equals(account.get("email"))) return "invalid";
                    jdbc.update("update user set password=? where id=?",passwords.hash(newPassword),owner);
                }
                security.invalidate(owner);
                return null;
            });
        } catch(org.springframework.dao.DataIntegrityViolationException e) { throw new BusinessException(400,"此邮箱不可用于绑定"); }
        // Throw only AFTER commit so wrong attempts survive; code and password never enter logs.
        if(result!=null) throw invalid();
    }
    private Map<String,Object> active(long userId) {
        var rows=jdbc.queryForList("select id,password,email,security_version from user where id=? and status='active' for update",userId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void rate(String name,int limit,boolean cooldown) {
        String bucket=digest("rate:"+name); Instant now=Instant.now();
        jdbc.update("insert into account_security_rate(bucket,window_start,requests,last_sent_at) values(?,?,0,?) on duplicate key update bucket=bucket",bucket,UtcDateTimes.toDatabase(now),UtcDateTimes.toDatabase(now.minusSeconds(3600)));
        var row=jdbc.queryForMap("select * from account_security_rate where bucket=? for update",bucket);
        Instant start=UtcDateTimes.toInstant(date(row.get("window_start")));
        Instant last=UtcDateTimes.toInstant(date(row.get("last_sent_at")));
        int requests=start.isAfter(now.minusSeconds(3600))?((Number)row.get("requests")).intValue():0;
        if(requests>=limit || cooldown && last.isAfter(now.minusSeconds(60))) throw new BusinessException(429,"请求过于频繁，请稍后重试");
        jdbc.update("update account_security_rate set window_start=?,requests=?,last_sent_at=? where bucket=?",
            UtcDateTimes.toDatabase(requests==0?now:start),requests+1,UtcDateTimes.toDatabase(now),bucket);
    }
    private String digest(String text) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException e) { throw new IllegalStateException("验证码摘要不可用",e); }
    }
    private static java.time.LocalDateTime date(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (java.time.LocalDateTime)value;
    }
    private static BusinessException invalid() { return new BusinessException(400,"验证码无效、已过期或操作不符合条件"); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${ACCOUNT_SECURITY_CLEANUP_DELAY:3600000}",initialDelayString="${ACCOUNT_SECURITY_CLEANUP_INITIAL_DELAY:3600000}")
    public void cleanup() {
        transaction.executeWithoutResult(s->{
            jdbc.update("delete from account_email_challenge where expires_at<? limit 1000",UtcDateTimes.toDatabase(Instant.now().minusSeconds(86400)));
            jdbc.update("delete from account_security_rate where last_sent_at<? limit 1000",UtcDateTimes.toDatabase(Instant.now().minusSeconds(86400)));
        });
    }
    public void deleteForUser(long userId) {
        jdbc.update("delete from account_email_challenge where user_id=?",userId);
        jdbc.update("update user_notification_channel set reminder_email=null,telegram_chat_id=null,telegram_enabled=false where user_id=?",userId);
        jdbc.update("delete from telegram_binding_challenge where user_id=?",userId);
    }
}
