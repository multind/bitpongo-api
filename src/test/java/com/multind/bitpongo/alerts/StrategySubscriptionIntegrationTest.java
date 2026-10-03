package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.FearGreedService;
import java.time.Instant;
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
}
