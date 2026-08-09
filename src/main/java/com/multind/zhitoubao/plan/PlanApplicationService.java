package com.multind.zhitoubao.plan;

import com.multind.zhitoubao.common.api.BusinessException;
import com.multind.zhitoubao.exchange.ExchangeRepository;
import com.multind.zhitoubao.market.PriceCache;
import com.multind.zhitoubao.scheduler.PlanScheduleService;
import com.multind.zhitoubao.strategy.CoinEntity;
import com.multind.zhitoubao.strategy.CoinRepository;
import com.multind.zhitoubao.strategy.StrategyEntity;
import com.multind.zhitoubao.strategy.StrategyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.multind.zhitoubao.plan.PlanDtos.PlanView;

@Service
public class PlanApplicationService {
    private static final Set<String> STATUSES = Set.of("active", "stop", "close");
    private final PlanRepository plans;
    private final StrategyRepository strategies;
    private final CoinRepository coins;
    private final OrderRepository orders;
    private final SnapshotRepository snapshots;
    private final ExchangeRepository exchanges;
    private final PriceCache prices;
    private final PortfolioCalculator calculator;
    private final PlanScheduleService schedules;
    private final Clock clock;

    @Autowired
    public PlanApplicationService(
            PlanRepository plans, StrategyRepository strategies, CoinRepository coins,
            OrderRepository orders, SnapshotRepository snapshots, ExchangeRepository exchanges,
            PriceCache prices, PortfolioCalculator calculator,
            ObjectProvider<PlanScheduleService> schedules) {
        this(plans, strategies, coins, orders, snapshots, exchanges, prices, calculator,
                schedules.getIfAvailable(), Clock.systemUTC());
    }

    PlanApplicationService(
            PlanRepository plans, StrategyRepository strategies, CoinRepository coins,
            OrderRepository orders, SnapshotRepository snapshots, ExchangeRepository exchanges,
            PriceCache prices, PortfolioCalculator calculator, PlanScheduleService schedules, Clock clock) {
        this.plans = plans;
        this.strategies = strategies;
        this.coins = coins;
        this.orders = orders;
        this.snapshots = snapshots;
        this.exchanges = exchanges;
        this.prices = prices;
        this.calculator = calculator;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanView> active(long userId) {
        return plans.findByUserIdAndStatusNot(userId, "close").stream()
                .map(plan -> view(plan, userId, false)).toList();
    }

    @Transactional(readOnly = true)
    public PlanView detail(long userId, long planId) {
        return view(owned(userId, planId), userId, true);
    }

    @Transactional
    public void updateStatus(long userId, long planId, String status) {
        if (!STATUSES.contains(status)) throw new BusinessException(400, "交易计划状态无效");
        PlanEntity plan = owned(userId, planId);
        plan.setStatus(status);
        plans.save(plan);
        if (schedules == null) return;
        Runnable scheduleMutation;
        if ("active".equals(status)) {
            StrategyEntity strategy = strategies.findByIdAndUserId(plan.getStrategyId(), userId)
                    .orElseThrow(() -> new BusinessException(404, "定投策略不存在"));
            String cron = com.multind.zhitoubao.strategy.StrategyApplicationService.normalizeCron(strategy.getCron());
            scheduleMutation = () -> schedules.resume(planId, cron);
        } else if ("close".equals(status)) {
            scheduleMutation = () -> schedules.remove(planId);
        } else {
            scheduleMutation = () -> schedules.pause(planId);
        }
        afterCommit(scheduleMutation);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private PlanEntity owned(long userId, long planId) {
        return plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "交易计划不存在"));
    }

    private PlanView view(PlanEntity plan, long userId, boolean includeOrders) {
        List<CoinEntity> planCoins = coins.findByPlanIdAndUserId(plan.getId(), userId);
        String exchange = exchanges.findByIdAndUserId(plan.getExchangeId(), userId)
                .map(value -> value.getExchange()).orElse("binance");
        List<PortfolioCalculator.Position> positions = planCoins.stream().map(coin -> {
            BigDecimal price = prices.getFresh(exchange, coin.getSymbol() + "/USDT", clock.instant())
                    .orElse(coin.getAverage() == null ? BigDecimal.ZERO : coin.getAverage());
            BigDecimal amount = coin.getTotalAmount() == null ? BigDecimal.ZERO : coin.getTotalAmount();
            BigDecimal average = coin.getAverage() == null ? BigDecimal.ZERO : coin.getAverage();
            coin.setIncome(amount.multiply(price.subtract(average)));
            return new PortfolioCalculator.Position(coin.getTotalAmount(), price);
        }).toList();
        BigDecimal totalValue = calculator.value(positions);
        BigDecimal totalRevenue = calculator.revenue(totalValue, plan.getTotalFunds());
        BigDecimal totalRatio = calculator.ratio(totalValue, plan.getTotalFunds());
        StrategyEntity strategy = strategies.findByIdAndUserId(plan.getStrategyId(), userId).orElse(null);
        List<OrderEntity> planOrders = includeOrders
                ? orders.findByPlanIdAndUserId(plan.getId(), userId) : List.of();
        List<SnapshotEntity> planSnapshots = snapshots
                .findByPlanIdAndUserIdOrderByCreatedAtAsc(plan.getId(), userId);
        return new PlanView(plan.getId(), plan.getTotalFunds(), totalRevenue, totalRatio,
                totalValue, plan.getNextTime(), plan.getStatus(), plan.getUserId(), plan.getTriggeredCount(),
                plan.getCreatedAt(), strategy, planCoins, planOrders, planSnapshots);
    }
}
