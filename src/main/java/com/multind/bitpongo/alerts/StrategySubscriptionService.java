package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.market.FearGreedService;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("!test")
public class StrategySubscriptionService {
    public record Settings(boolean enabled,int fearThreshold,int greedThreshold) {}
    private final JdbcTemplate jdbc;
    private final AlertNotificationBridge notifications;
    public StrategySubscriptionService(JdbcTemplate jdbc,AlertNotificationBridge notifications) { this.jdbc=jdbc;this.notifications=notifications; }
    public Settings get(long userId) {
        return jdbc.query("select * from strategy_subscription where user_id=?",(rs,i)->new Settings(rs.getBoolean("enabled"),rs.getInt("fear_threshold"),rs.getInt("greed_threshold")),userId).stream().findFirst().orElse(null);
    }
    @Transactional public void save(long userId,Settings settings) {
        if(settings==null || settings.fearThreshold()<0 || settings.greedThreshold()>100 || settings.fearThreshold()>=settings.greedThreshold()) throw new BusinessException(400,"指数阈值无效");
        activeUser(userId);
        var current=get(userId);
        if(settings.equals(current)) return;
        jdbc.update("""
            insert into strategy_subscription(user_id,enabled,fear_threshold,greed_threshold,valid_from)
            values(?,?,?,?,?) on duplicate key update enabled=?,fear_threshold=?,greed_threshold=?,valid_from=?,last_observed_at=null
            """,userId,settings.enabled(),settings.fearThreshold(),settings.greedThreshold(),PriceAlertService.utc(Instant.now()),settings.enabled(),settings.fearThreshold(),settings.greedThreshold(),PriceAlertService.utc(Instant.now()));
    }
    @Transactional public void delete(long userId) { activeUser(userId);jdbc.update("delete from strategy_subscription where user_id=?",userId); }
    public List<Long> activeUsers() { return jdbc.queryForList("select s.user_id from strategy_subscription s join user u on u.id=s.user_id where s.enabled=true and u.status='active'",Long.class); }
    private void activeUser(long userId) {
        if(jdbc.queryForList("select id from user where id=? and status='active' for update",userId).isEmpty()) throw new BusinessException(401,"账号不可用");
    }
    @Transactional public void sample(long userId,FearGreedService.Point point) {
        if(point==null || point.observedAt()==null || point.value()<0 || point.value()>100 || point.observedAt().isBefore(Instant.now().minusSeconds(172800)) || point.observedAt().isAfter(Instant.now().plusSeconds(300))) return;
        if(jdbc.queryForList("select id from user where id=? and status='active' for update",userId).isEmpty()) return;
        var rows=jdbc.queryForList("select * from strategy_subscription where user_id=? and enabled=true for update",userId);
        if(rows.isEmpty()) return;
        var row=rows.getFirst();
        var validFrom=PriceAlertService.instant(row.get("valid_from"));
        var last=PriceAlertService.instant(row.get("last_observed_at"));
        if(!point.observedAt().isAfter(validFrom) || (last!=null && !point.observedAt().isAfter(last))) return;
        jdbc.update("update strategy_subscription set last_observed_at=? where user_id=?",PriceAlertService.utc(point.observedAt()),userId);
        if(point.value()>((Number)row.get("fear_threshold")).intValue() && point.value()<((Number)row.get("greed_threshold")).intValue()) return;
        String key="strategy:fear-greed:"+userId+":"+point.observedAt().getEpochSecond();
        if(jdbc.queryForObject("select count(*) from alert_event where event_key=?",Integer.class,key)>0) return;
        String summary="Fear & Greed Index: "+point.value()+" / 100";
        jdbc.update("insert into alert_event(user_id,event_type,event_key,title,body,occurred_at) values(?,?,?,?,?,?)",userId,"STRATEGY_SIGNAL",key,"Fear & Greed Index",summary,PriceAlertService.utc(point.observedAt()));
        notifications.strategy(userId,key,summary,point.observedAt());
    }
}
