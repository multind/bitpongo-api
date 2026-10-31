package com.multind.bitpongo.plan;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.common.time.UtcDateTimes;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.market.PriceCache;
import com.multind.bitpongo.scheduler.PlanScheduleService;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.CoinRepository;
import com.multind.bitpongo.strategy.StrategyEntity;
import com.multind.bitpongo.strategy.StrategyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.multind.bitpongo.plan.PlanDtos.PlanView;
import static com.multind.bitpongo.plan.PlanDtos.OrderPage;
import static com.multind.bitpongo.plan.PlanDtos.OrderView;

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
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ActivePage(List<PlanView> items, int page, int size, long total, long activeTotal, boolean hasMore) {}

    @Transactional(readOnly = true)
    public ActivePage activePage(long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new BusinessException(400, "分页参数无效");
        var result = plans.findByUserIdAndStatusNot(userId, "close", PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return new ActivePage(result.getContent().stream().map(plan -> view(plan, userId, false, true)).toList(), page, size, result.getTotalElements(), plans.countByUserIdAndStatus(userId,"active"), result.hasNext());
    }

    @Transactional(readOnly = true)
    public PlanView detail(long userId, long planId) {
        return detail(userId, planId, true);
    }

    @Transactional(readOnly = true)
    public PlanView detail(long userId, long planId, boolean includeOrders) {
        return view(owned(userId, planId), userId, includeOrders);
    }

    @Transactional(readOnly = true)
    public OrderPage orders(long userId, long planId, int page, int size) {
        owned(userId, planId);
        if (page < 0) throw new BusinessException(400, "页码不能小于 0");
        int pageSize = Math.max(1, Math.min(size, 50));
        var result = orders.findByPlanIdAndUserId(
                planId, userId, PageRequest.of(page, pageSize,
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new OrderPage(result.getContent().stream().map(OrderView::from).toList(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.hasNext());
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
            String cron = com.multind.bitpongo.strategy.StrategyApplicationService.normalizeCron(strategy.getCron());
            java.time.ZoneId zone = com.multind.bitpongo.strategy.StrategyApplicationService
                    .scheduleZone(strategy.getScheduleTimezone());
            scheduleMutation = () -> schedules.resume(planId, cron, zone);
        } else if ("close".equals(status)) {
            scheduleMutation = () -> schedules.remove(planId);
        } else {
            scheduleMutation = () -> schedules.pause(planId);
        }
        afterCommit(scheduleMutation);
    }

    @Transactional
    public void rename(long userId, long planId, String name) {
        PlanEntity plan = owned(userId, planId);
        StrategyEntity strategy = strategies.findByIdAndUserId(plan.getStrategyId(), userId)
                .orElseThrow(() -> new BusinessException(404, "定投策略不存在"));
        strategy.setName(name.trim());
        strategies.save(strategy);
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
        return view(plan,userId,includeOrders,false);
    }
    private PlanView view(PlanEntity plan, long userId, boolean includeOrders, boolean summary) {
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
        List<SnapshotEntity> planSnapshots;
        if(summary) {
            planSnapshots = new java.util.ArrayList<>(snapshots.findByPlanIdAndUserIdOrderByCreatedAtDesc(plan.getId(),userId,PageRequest.of(0,120)));
            java.util.Collections.reverse(planSnapshots);
        } else planSnapshots = snapshots.findByPlanIdAndUserIdOrderByCreatedAtAsc(plan.getId(),userId);
        return new PlanView(plan.getId(), plan.getTotalFunds(), totalRevenue, totalRatio,
                totalValue, UtcDateTimes.toInstant(plan.getNextTime()),
                UtcDateTimes.toInstant(plan.getNextTime()), plan.getStatus(), plan.getUserId(),
                plan.getTriggeredCount(), UtcDateTimes.toInstant(plan.getCreatedAt()),
                strategy, planCoins, planOrders, planSnapshots);
    }
}
