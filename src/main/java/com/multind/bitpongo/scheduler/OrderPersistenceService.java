package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.exchange.OrderResult;
import com.multind.bitpongo.plan.OrderEntity;
import com.multind.bitpongo.plan.OrderRepository;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.CoinRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(OrderIntentRepository.class)
public class OrderPersistenceService {
    private final OrderIntentRepository intents;
    private final OrderRepository orders;
    private final CoinRepository coins;
    private final PlanRepository plans;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId schedulingZone;

    @Autowired
    public OrderPersistenceService(
            OrderIntentRepository intents, OrderRepository orders,
            CoinRepository coins, PlanRepository plans, JdbcTemplate jdbc,
            @Value("${zhitoubao.scheduling-zone:Asia/Shanghai}") String schedulingZone) {
        this(intents, orders, coins, plans, jdbc, Clock.systemUTC(), ZoneId.of(schedulingZone));
    }

    OrderPersistenceService(
            OrderIntentRepository intents, OrderRepository orders,
            CoinRepository coins, PlanRepository plans, JdbcTemplate jdbc, Clock clock) {
        this(intents, orders, coins, plans, jdbc, clock, clock.getZone());
    }

    OrderPersistenceService(
            OrderIntentRepository intents, OrderRepository orders,
            CoinRepository coins, PlanRepository plans, JdbcTemplate jdbc,
            Clock clock, ZoneId schedulingZone) {
        this.intents = intents;
        this.orders = orders;
        this.coins = coins;
        this.plans = plans;
        this.jdbc = jdbc;
        this.clock = clock;
        this.schedulingZone = schedulingZone;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(OrderIntentEntity intent, String status) {
        intent.setStatus(status);
        intent.setAttempts((intent.getAttempts() == null ? 0 : intent.getAttempts()) + 1);
        intent.setUpdatedAt(now());
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirm(OrderIntentEntity intent, OrderResult result) {
        confirmOwned(intent, result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markAfterReconciliation(
            OrderIntentEntity intent, String status, LocalDateTime leaseAcquiredAt) {
        OrderIntentEntity owned = ownedLease(intent, leaseAcquiredAt);
        if (owned == null) return false;
        owned.setStatus(status);
        owned.setAttempts((owned.getAttempts() == null ? 0 : owned.getAttempts()) + 1);
        owned.setUpdatedAt(now());
        intents.save(owned);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean confirmAfterReconciliation(
            OrderIntentEntity intent, OrderResult result, LocalDateTime leaseAcquiredAt) {
        OrderIntentEntity owned = ownedLease(intent, leaseAcquiredAt);
        if (owned == null) return false;
        confirmOwned(owned, result);
        return true;
    }

    private void confirmOwned(OrderIntentEntity intent, OrderResult result) {
        if (result.quantity() == null || result.quantity().signum() <= 0) {
            throw new IllegalArgumentException("不能将未成交订单记入持仓");
        }
        if (orders.findByClientOrderId(intent.getClientOrderId()).isPresent()) {
            intent.setStatus("CONFIRMED");
            intent.setUpdatedAt(now());
            intents.save(intent);
            return;
        }
        OrderEntity order = new OrderEntity();
        order.setSymbol(toInternalSymbol(result.symbol()));
        order.setOrderNo(result.orderId());
        order.setClientOrderId(intent.getClientOrderId());
        order.setTotalAmount(result.quantity());
        order.setAveragePrice(result.averagePrice());
        order.setTotalCost(result.totalCost());
        order.setFee(singleAssetFee(result.fees()));
        order.setUserId(intent.getUserId());
        order.setCreatedAt(now());
        order.setPlanId(intent.getPlanId());
        orders.save(order);

        CoinEntity coin = coins.findByIdAndUserId(intent.getCoinId(), intent.getUserId())
                .orElseThrow(() -> new IllegalStateException("订单对应币种不存在"));
        BigDecimal oldAmount = safe(coin.getTotalAmount());
        BigDecimal acquired = holdingQuantity(result);
        BigDecimal newAmount = oldAmount.add(acquired);
        BigDecimal weightedCost = oldAmount.multiply(safe(coin.getAverage()))
                .add(safe(result.totalCost()))
                .add(feeFor(result, "USDT"));
        coin.setTotalAmount(newAmount);
        coin.setAverage(newAmount.signum() == 0 ? BigDecimal.ZERO
                : weightedCost.divide(newAmount, 18, RoundingMode.HALF_UP));
        coin.setIncome(BigDecimal.ZERO);
        coins.save(coin);

        PlanEntity plan = plans.findById(intent.getPlanId())
                .orElseThrow(() -> new IllegalStateException("订单对应计划不存在"));
        BigDecimal invested = result.totalCost();
        invested = invested.add(feeFor(result, "USDT"));
        plan.setTotalFunds(safe(plan.getTotalFunds()).add(invested));
        plans.save(plan);
        intent.setStatus("CONFIRMED");
        intent.setUpdatedAt(now());
        intents.save(intent);
    }

    private OrderIntentEntity ownedLease(OrderIntentEntity intent, LocalDateTime leaseAcquiredAt) {
        return intents.findByIdForUpdate(intent.getId())
                .filter(current -> "RECONCILING".equals(current.getStatus()))
                .filter(current -> leaseAcquiredAt.equals(current.getUpdatedAt()))
                .orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginTrigger(long planId, Instant scheduledFireTime) {
        LocalDateTime fireTime = LocalDateTime.ofInstant(scheduledFireTime, clock.getZone());
        int inserted = jdbc.update("""
                INSERT IGNORE INTO plan_fire_execution(plan_id, scheduled_fire_time, created_at)
                VALUES (?, ?, ?)
                """, planId, fireTime, now());
        if (inserted == 0) return false;
        PlanEntity plan = plans.findById(planId)
                .orElseThrow(() -> new IllegalStateException("交易计划不存在"));
        plan.setTriggeredCount((plan.getTriggeredCount() == null ? 0 : plan.getTriggeredCount()) + 1);
        plans.save(plan);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateNextFireTime(long planId, Instant nextFireTime) {
        plans.findById(planId).ifPresent(plan -> {
            plan.setNextTime(LocalDateTime.ofInstant(nextFireTime, schedulingZone));
            plans.save(plan);
        });
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), clock.getZone()); }
    private static BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal holdingQuantity(OrderResult result) {
        String baseAsset = result.symbol() != null && result.symbol().endsWith("USDT")
                ? result.symbol().substring(0, result.symbol().length() - 4) : "";
        return result.quantity().subtract(feeFor(result, baseAsset));
    }
    private static BigDecimal feeFor(OrderResult result, String asset) {
        if (asset == null || asset.isBlank()) return BigDecimal.ZERO;
        return safe(result.fees().get(asset.toUpperCase()));
    }
    private static BigDecimal singleAssetFee(Map<String, BigDecimal> fees) {
        return fees.size() == 1 ? fees.values().iterator().next() : BigDecimal.ZERO;
    }
    private static String toInternalSymbol(String symbol) {
        return symbol != null && symbol.endsWith("USDT")
                ? symbol.substring(0, symbol.length() - 4) + "/USDT" : symbol;
    }
}
