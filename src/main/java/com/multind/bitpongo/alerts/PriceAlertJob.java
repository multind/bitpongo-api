package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.MarketDataService;
import com.multind.bitpongo.market.MarketDtos;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("!test")
public class PriceAlertJob {
    private final PriceAlertService alerts;
    private final MarketDataService market;
    public PriceAlertJob(PriceAlertService alerts, MarketDataService market) { this.alerts = alerts; this.market = market; }
    @Scheduled(fixedDelayString="${PRICE_ALERT_POLL_DELAY:15000}") public void poll() {
        var rules = alerts.activeRules();
        if (rules.isEmpty()) return;
        try {
            var prices = new HashMap<String,MarketDtos.Ticker>();
            market.tickers().forEach(t -> prices.put(t.symbol(), t));
            var candles = new HashMap<String,List<MarketDtos.Candle>>();
            for (var rule : rules) {
                try {
                    String symbol = (String) rule.get("symbol");
                    String frequency = (String) rule.get("frequency");
                    var ticker = prices.get(symbol);
                    if (ticker == null || ticker.updatedAt().isBefore(Instant.now().minusSeconds(60))) continue;
                    var price = ticker.lastPrice();
                    Instant sample = ticker.updatedAt();
                    long bucket = 0;
                    if (frequency.equals("EACH_CANDLE") || frequency.equals("CANDLE_CLOSE")) {
                        String interval = (String) rule.get("candle_interval");
                        var series = candles.computeIfAbsent(symbol + ":" + interval, ignored -> market.candles(symbol, interval, 2));
                        var c = series.stream().filter(v -> !frequency.equals("CANDLE_CLOSE") || v.closeTime() < System.currentTimeMillis()).reduce((a,b) -> b).orElse(null);
                        if (c == null) continue;
                        bucket = c.openTime();
                        if (frequency.equals("CANDLE_CLOSE")) { price = c.close(); sample = Instant.ofEpochMilli(c.closeTime()); }
                    }
                    alerts.sample(((Number)rule.get("user_id")).longValue(), ((Number)rule.get("id")).longValue(), price, sample, bucket);
                } catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("提醒采样失败 ruleId={} errorType={}", rule.get("id"), e.getClass().getSimpleName()); }
            }
        } catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("提醒行情加载失败 errorType={}", e.getClass().getSimpleName()); }
    }
}
