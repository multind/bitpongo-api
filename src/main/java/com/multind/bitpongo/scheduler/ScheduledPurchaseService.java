package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.exchange.*;
import com.multind.bitpongo.market.PriceCache;
import com.multind.bitpongo.plan.*;
import com.multind.bitpongo.strategy.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ScheduledPurchaseService implements ScheduledPurchaseUseCase {
    private static final Logger log = LoggerFactory.getLogger(ScheduledPurchaseService.class);
    private static final Set<String> TERMINAL_ORDER_STATUSES = Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED");
    private final PlanRepository plans;
    private final StrategyRepository strategies;
    private final CoinRepository coins;
    private final OrderRepository orders;
    private final ExchangeRepository exchanges;
    private final OrderIntentRepository intents;
    private final ExchangeGatewayRegistry gateways;
    private final OrderSizingService sizing;
    private final PriceCache prices;
    private final OrderIdFactory orderIds;
    private final OrderPersistenceService persistence;
    private final Clock clock;

    @Autowired
    public ScheduledPurchaseService(
            PlanRepository plans, StrategyRepository strategies,
            CoinRepository coins, OrderRepository orders,
            ExchangeRepository exchanges, OrderIntentRepository intents,
            ExchangeGatewayRegistry gateways, OrderSizingService sizing, PriceCache prices,
            OrderIdFactory orderIds, OrderPersistenceService persistence) {
        this(plans, strategies, coins, orders, exchanges, intents, gateways, sizing, prices,
                orderIds, persistence, Clock.systemUTC());
    }

    ScheduledPurchaseService(
            PlanRepository plans, StrategyRepository strategies,
            CoinRepository coins, OrderRepository orders,
            ExchangeRepository exchanges, OrderIntentRepository intents,
            ExchangeGatewayRegistry gateways, OrderSizingService sizing, PriceCache prices,
            OrderIdFactory orderIds, OrderPersistenceService persistence, Clock clock) {
        this.plans = plans; this.strategies = strategies; this.coins = coins; this.orders = orders;
        this.exchanges = exchanges; this.intents = intents; this.gateways = gateways;
        this.sizing = sizing; this.prices = prices; this.orderIds = orderIds;
        this.persistence = persistence; this.clock = clock;
    }

    @Override
    public void execute(long planId, Instant scheduledFireTime) {
        PlanEntity plan = plans.findById(planId).orElse(null);
        if (plan == null || !"active".equals(plan.getStatus())) return;
        StrategyEntity strategy = strategies.findById(plan.getStrategyId()).orElse(null);
        ExchangeEntity exchange = exchanges.findByIdAndUserId(plan.getExchangeId(), plan.getUserId()).orElse(null);
        if (strategy == null || exchange == null) return;
        ExchangeGateway gateway = gateways.require(exchange.getExchange());
        ExchangeCredentials credentials = new ExchangeCredentials(
                exchange.getAccessKey(), exchange.getSecretKey(), exchange.getPassword());
        persistence.beginTrigger(planId, scheduledFireTime);
        for (CoinEntity coin : coins.findByPlanIdAndUserId(planId, plan.getUserId())) {
            String marketSymbol = coin.getSymbol().toUpperCase() + "USDT";
            String internalSymbol = coin.getSymbol().toUpperCase() + "/USDT";
            BigDecimal price = prices.getFresh(exchange.getExchange(), internalSymbol, clock.instant()).orElse(null);
            if (price == null) {
                log.warn("无新鲜行情，跳过买入 planId={} coin={}", planId, coin.getSymbol());
                continue;
            }
            if (shouldSkipAverageDown(planId, strategy, coin, internalSymbol, price)) continue;
            String clientOrderId = orderIds.create(planId, marketSymbol, scheduledFireTime);
            if (intents.findByClientOrderId(clientOrderId).isPresent()) continue;
            BigDecimal quantity = sizing.calculate(BigDecimal.valueOf(strategy.getInstalment()),
                    new BigDecimal(coin.getProportion()), price, gateway.getMarketRules(marketSymbol));
            OrderIntentEntity intent = claim(plan, coin, marketSymbol, quantity, clientOrderId, scheduledFireTime);
            if (intent == null) continue;
            try {
                persistence.mark(intent, "SUBMITTING");
                OrderResult result = gateway.marketBuy(credentials, marketSymbol, quantity, clientOrderId);
                String resultStatus = result.status() == null ? "" : result.status().toUpperCase(Locale.ROOT);
                if (TERMINAL_ORDER_STATUSES.contains(resultStatus) && result.quantity().signum() > 0) {
                    persistence.confirm(intent, result);
                } else if (Set.of("CANCELED", "EXPIRED", "REJECTED").contains(resultStatus)) {
                    persistence.mark(intent, resultStatus);
                } else {
                    persistence.mark(intent, "PENDING_RECONCILIATION");
                }
            } catch (AmbiguousOrderException ambiguous) {
                persistence.mark(intent, "PENDING_RECONCILIATION");
            } catch (RuntimeException failure) {
                persistence.mark(intent, "FAILED");
                log.error("计划币种下单失败，planId={}, coinId={}", planId, coin.getId(), failure);
            }
        }
    }

    @Override
    public void updateNextFireTime(long planId, Instant nextFireTime) {
        persistence.updateNextFireTime(planId, nextFireTime);
    }

    private boolean shouldSkipAverageDown(
            long planId, StrategyEntity strategy, CoinEntity coin, String symbol, BigDecimal price) {
        if (!Boolean.TRUE.equals(coin.getAverageDown())) return false;
        if ("total_average".equals(strategy.getCondition())) {
            return coin.getAverage() != null && coin.getAverage().signum() > 0
                    && price.compareTo(coin.getAverage()) >= 0;
        }
        if ("last_average".equals(strategy.getCondition())) {
            return orders.findFirstByPlanIdAndSymbolOrderByCreatedAtDesc(planId, symbol)
                    .map(order -> price.compareTo(order.getAveragePrice()) >= 0).orElse(false);
        }
        return true;
    }

    private OrderIntentEntity claim(
            PlanEntity plan, CoinEntity coin, String symbol,
            BigDecimal quantity, String clientOrderId, Instant fireTime) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        OrderIntentEntity intent = new OrderIntentEntity();
        intent.setClientOrderId(clientOrderId); intent.setPlanId(plan.getId()); intent.setCoinId(coin.getId());
        intent.setUserId(plan.getUserId()); intent.setSymbol(symbol); intent.setQuantity(quantity);
        intent.setScheduledFireTime(LocalDateTime.ofInstant(fireTime, clock.getZone()));
        intent.setStatus("READY"); intent.setAttempts(0); intent.setCreatedAt(now); intent.setUpdatedAt(now);
        try { return intents.saveAndFlush(intent); }
        catch (DataIntegrityViolationException claimedElsewhere) { return null; }
    }
}
