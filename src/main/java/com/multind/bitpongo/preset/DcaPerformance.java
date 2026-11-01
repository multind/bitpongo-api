package com.multind.bitpongo.preset;

import com.multind.bitpongo.market.PriceCache;
import com.multind.bitpongo.plan.PortfolioCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class DcaPerformance {
    record Position(String symbol, BigDecimal amount) {}
    record Result(BigDecimal revenue, BigDecimal ratio) {}
    static Result calculate(PriceCache cache, String exchange, List<Position> positions, BigDecimal funds, Instant now) {
        if (funds == null || funds.signum() <= 0 || positions.isEmpty()) return null;
        var values = new ArrayList<PortfolioCalculator.Position>();
        for (var position : positions) {
            if (position.amount() == null || position.amount().signum() < 0) return null;
            if (position.amount().signum() == 0) continue;
            var price = cache.getFresh(exchange, position.symbol() + "/USDT", now);
            if (price.isEmpty()) return null;
            values.add(new PortfolioCalculator.Position(position.amount(), price.get()));
        }
        if (values.isEmpty()) return null;
        var calculator = new PortfolioCalculator();
        var value = calculator.value(values);
        return new Result(calculator.revenue(value, funds), calculator.ratio(value, funds));
    }
}
