package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.FearGreedService;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class FngStrategyJob {
    private final FngStrategyService strategies;
    private final FearGreedService indicators;

    public FngStrategyJob(FngStrategyService strategies, FearGreedService indicators) {
        this.strategies = strategies;
        this.indicators = indicators;
    }

    @Scheduled(fixedDelayString = "${FNG_STRATEGY_POLL_DELAY:600000}", initialDelayString = "${FNG_STRATEGY_INITIAL_DELAY:600000}")
    public void poll() {
        try {
            var active = strategies.activeStrategies();
            if (active.isEmpty()) return;
            var point = indicators.latestFresh();
            for (var strategy : active) {
                try {
                    strategies.sample(strategy.id(), strategy.userId(), point);
                } catch (RuntimeException exception) {
                    LoggerFactory.getLogger(getClass()).warn(
                            "F&G 策略采样失败 strategyId={} errorType={}", strategy.id(), exception.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(getClass()).warn("F&G 策略采样暂不可用 errorType={}", exception.getClass().getSimpleName());
        }
    }
}