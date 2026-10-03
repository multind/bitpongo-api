package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.FearGreedService;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class StrategySubscriptionIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired StrategySubscriptionService subscriptions;
    @Test void matchingNewIndexEnqueuesOnceWithoutCreatingTradingPlan() {
        int plans=count("plan");
        subscriptions.save(user,new StrategySubscriptionService.Settings(true,25,75));
        var point=new FearGreedService.Point(20,"Extreme Fear",Instant.now().plusSeconds(1));
        subscriptions.sample(user,point);subscriptions.sample(user,point);
        assertThat(count("alert_event")).isEqualTo(1);
        assertThat(count("push_delivery")).isEqualTo(1);
        assertThat(count("plan")).isEqualTo(plans);
        assertThat(subscriptions.get(user).enabled()).isTrue();
        assertThat(subscriptions.get(user+1)).isNull();
    }
    @Test void pausedHistoricalAndNeutralIndexDoNotNotify() {
        subscriptions.save(user,new StrategySubscriptionService.Settings(true,25,75));
        subscriptions.sample(user,new FearGreedService.Point(20,"Extreme Fear",Instant.now().minusSeconds(3600)));
        subscriptions.sample(user,new FearGreedService.Point(50,"Neutral",Instant.now().plusSeconds(1)));
        subscriptions.save(user,new StrategySubscriptionService.Settings(false,25,75));
        subscriptions.sample(user,new FearGreedService.Point(80,"Extreme Greed",Instant.now().plusSeconds(2)));
        assertThat(count("alert_event")).isZero();
        assertThatThrownBy(()->subscriptions.save(user,new StrategySubscriptionService.Settings(true,80,20))).hasMessageContaining("阈值");
    }
    @Test void concurrentSamplesProduceExactlyOneNotification() throws Exception {
        subscriptions.save(user,new StrategySubscriptionService.Settings(true,25,75));
        var point=new FearGreedService.Point(75,"Greed",Instant.now().plusSeconds(1));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->subscriptions.sample(user,point));
            var second=pool.submit(()->subscriptions.sample(user,point));
            first.get();second.get();
        }
        assertThat(count("alert_event")).isEqualTo(1);
        assertThat(count("push_delivery")).isEqualTo(1);
    }
    @Test void rollbackRestoresCursorAndAllowsRetry() {
        subscriptions.save(user,new StrategySubscriptionService.Settings(true,25,75));
        var point=new FearGreedService.Point(25,"Fear",Instant.now().plusSeconds(1));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            subscriptions.sample(user,point);
            tx.setRollbackOnly();
        });
        assertThat(count("alert_event")).isZero();
        assertThat(count("push_delivery")).isZero();
        assertThat(jdbc.queryForObject("select last_observed_at from strategy_subscription where user_id=?",java.sql.Timestamp.class,user)).isNull();
        subscriptions.sample(user,point);
        assertThat(count("alert_event")).isEqualTo(1);
        assertThat(count("push_delivery")).isEqualTo(1);
    }
    @Test void unchangedSettingsDoNotResetObservationCursor() {
        var settings=new StrategySubscriptionService.Settings(true,25,75);
        subscriptions.save(user,settings);
        var point=new FearGreedService.Point(20,"Extreme Fear",Instant.now().plusSeconds(1));
        subscriptions.sample(user,point);
        var cursor=jdbc.queryForObject("select last_observed_at from strategy_subscription where user_id=?",java.sql.Timestamp.class,user);
        subscriptions.save(user,settings);
        assertThat(jdbc.queryForObject("select last_observed_at from strategy_subscription where user_id=?",java.sql.Timestamp.class,user)).isEqualTo(cursor);
        subscriptions.sample(user,point);
        assertThat(count("alert_event")).isEqualTo(1);
    }
}
