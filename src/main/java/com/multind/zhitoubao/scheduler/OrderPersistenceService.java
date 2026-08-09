package com.multind.zhitoubao.scheduler;

import com.multind.zhitoubao.exchange.OrderResult;
import com.multind.zhitoubao.plan.OrderEntity;
import com.multind.zhitoubao.plan.OrderRepository;
import com.multind.zhitoubao.plan.PlanEntity;
import com.multind.zhitoubao.plan.PlanRepository;
import com.multind.zhitoubao.strategy.CoinEntity;
import com.multind.zhitoubao.strategy.CoinRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(OrderIntentRepository.class)
public class OrderPersistenceService {
    private final OrderIntentRepository intents;
    private final OrderRepository orders;
    private final CoinRepository coins;
    private final PlanRepository plans;
    private final Clock clock = Clock.systemUTC();

    public OrderPersistenceService(
            OrderIntentRepository intents, OrderRepository orders,
            CoinRepository coins, PlanRepository plans) {
        this.intents = intents;
        this.orders = orders;
        this.coins = coins;
        this.plans = plans;
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
        order.setFee(BigDecimal.ZERO);
        order.setUserId(intent.getUserId());
        order.setCreatedAt(now());
        order.setPlanId(intent.getPlanId());
        orders.save(order);

        CoinEntity coin = coins.findByIdAndUserId(intent.getCoinId(), intent.getUserId())
                .orElseThrow(() -> new IllegalStateException("订单对应币种不存在"));
        BigDecimal oldAmount = safe(coin.getTotalAmount());
        BigDecimal newAmount = oldAmount.add(result.quantity());
        BigDecimal weightedCost = oldAmount.multiply(safe(coin.getAverage()))
                .add(result.quantity().multiply(result.averagePrice()));
        coin.setTotalAmount(newAmount);
        coin.setAverage(newAmount.signum() == 0 ? BigDecimal.ZERO
                : weightedCost.divide(newAmount, 18, RoundingMode.HALF_UP));
        coin.setIncome(BigDecimal.ZERO);
        coins.save(coin);

        PlanEntity plan = plans.findById(intent.getPlanId())
                .orElseThrow(() -> new IllegalStateException("订单对应计划不存在"));
        plan.setTotalFunds(safe(plan.getTotalFunds()).add(result.totalCost()));
        plans.save(plan);
        intent.setStatus("CONFIRMED");
        intent.setUpdatedAt(now());
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTrigger(long planId, Instant scheduledFireTime) {
        PlanEntity plan = plans.findById(planId)
                .orElseThrow(() -> new IllegalStateException("交易计划不存在"));
        plan.setTriggeredCount((plan.getTriggeredCount() == null ? 0 : plan.getTriggeredCount()) + 1);
        plans.save(plan);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), clock.getZone()); }
    private static BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static String toInternalSymbol(String symbol) {
        return symbol != null && symbol.endsWith("USDT")
                ? symbol.substring(0, symbol.length() - 4) + "/USDT" : symbol;
    }
}
