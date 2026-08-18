package com.multind.bitpongo.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("binanceMarketStreamHealth")
@ConditionalOnBean(BinanceMarketStreamLifecycle.class)
public class MarketStreamHealthIndicator implements HealthIndicator {
    private final BinanceMarketStreamLifecycle lifecycle;
    private final Duration maxSilence;
    private final Clock clock;

    @Autowired
    public MarketStreamHealthIndicator(
            BinanceMarketStreamLifecycle lifecycle,
            @Value("${zhitoubao.market.health-max-silence:120s}") Duration maxSilence) {
        this(lifecycle, maxSilence, Clock.systemUTC());
    }

    MarketStreamHealthIndicator(
            BinanceMarketStreamLifecycle lifecycle, Duration maxSilence, Clock clock) {
        this.lifecycle = lifecycle;
        this.maxSilence = maxSilence;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant lastMessage = lifecycle.lastMessageAt();
        boolean fresh = lastMessage != null
                && !lastMessage.plus(maxSilence).isBefore(clock.instant());
        if (lifecycle.isConnected() && fresh) {
            return Health.up().withDetail("lastMessageAt", lastMessage).build();
        }
        return Health.down()
                .withDetail("connected", lifecycle.isConnected())
                .withDetail("lastMessageAt", lastMessage == null ? "never" : lastMessage)
                .build();
    }
}
