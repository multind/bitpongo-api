package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.FearGreedService;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("!test")
public class StrategySignalJob {
    private final StrategySubscriptionService subscriptions;
    private final FearGreedService indicators;
    public StrategySignalJob(StrategySubscriptionService subscriptions,FearGreedService indicators) { this.subscriptions=subscriptions;this.indicators=indicators; }
    @Scheduled(fixedDelayString="${STRATEGY_SIGNAL_POLL_DELAY:600000}",initialDelayString="${STRATEGY_SIGNAL_INITIAL_DELAY:600000}")
    public void poll() {
        try {
            var users=subscriptions.activeUsers();if(users.isEmpty()) return;
            var point=indicators.latestFresh();
            for(long user:users) {
                try { subscriptions.sample(user,point); }
                catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("策略提醒失败 userId={} errorType={}",user,e.getClass().getSimpleName()); }
            }
        } catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("策略提醒采样暂不可用 errorType={}",e.getClass().getSimpleName()); }
    }
}
