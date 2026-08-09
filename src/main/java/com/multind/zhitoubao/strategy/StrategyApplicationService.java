package com.multind.zhitoubao.strategy;

import com.multind.zhitoubao.common.api.BusinessException;
import com.multind.zhitoubao.exchange.ExchangeRepository;
import com.multind.zhitoubao.plan.PlanEntity;
import com.multind.zhitoubao.plan.PlanRepository;
import com.multind.zhitoubao.scheduler.PlanScheduleService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.multind.zhitoubao.strategy.StrategyDtos.StrategyCreateRequest;
import static com.multind.zhitoubao.strategy.StrategyDtos.StrategyCreatedData;

@Service
public class StrategyApplicationService {
    private static final PlanScheduleService DEFERRED_SCHEDULER = new PlanScheduleService() {
        public void schedule(long planId, String cron) {}
        public void pause(long planId) {}
        public void resume(long planId, String cron) {}
        public void remove(long planId) {}
    };

    private final StrategyRepository strategies;
    private final PlanRepository plans;
    private final CoinRepository coins;
    private final ExchangeRepository exchanges;
    private final PlanScheduleService schedules;
    private final Clock clock;

    @Autowired
    public StrategyApplicationService(
            StrategyRepository strategies,
            PlanRepository plans,
            CoinRepository coins,
            ExchangeRepository exchanges,
            ObjectProvider<PlanScheduleService> schedules) {
        this(strategies, plans, coins, exchanges,
                schedules.getIfAvailable(() -> DEFERRED_SCHEDULER), Clock.systemUTC());
    }

    StrategyApplicationService(
            StrategyRepository strategies,
            PlanRepository plans,
            CoinRepository coins,
            ExchangeRepository exchanges,
            PlanScheduleService schedules,
            Clock clock) {
        this.strategies = strategies;
        this.plans = plans;
        this.coins = coins;
        this.exchanges = exchanges;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Transactional
    public StrategyCreatedData create(long userId, StrategyCreateRequest request) {
        exchanges.findByIdAndUserId(request.exchangeId(), userId)
                .orElseThrow(() -> new BusinessException(404, "交易所不存在"));
        BigDecimal total = request.coins().stream()
                .map(StrategyDtos.CoinRequest::proportion)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException(400, "币种比例合计必须为100");
        }
        String quartzCron = normalizeCron(request.cron());
        CronExpression expression;
        try {
            expression = new CronExpression(quartzCron);
        } catch (java.text.ParseException exception) {
            throw new BusinessException(400, "Cron表达式无效");
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        StrategyEntity strategy = new StrategyEntity();
        strategy.setName(request.name());
        try {
            strategy.setInstalment(request.instalment().intValueExact());
        } catch (ArithmeticException exception) {
            throw new BusinessException(400, "定投金额必须为整数");
        }
        strategy.setExchangeId(request.exchangeId());
        strategy.setFrequency(request.frequency());
        strategy.setCron(request.cron());
        strategy.setCondition(request.condition());
        strategy.setUserId(userId);
        strategy.setCreatedAt(now);
        strategy = strategies.save(strategy);

        PlanEntity plan = new PlanEntity();
        plan.setTotalFunds(BigDecimal.ZERO);
        plan.setTotalRevenue(BigDecimal.ZERO);
        plan.setTotalRatio(BigDecimal.ZERO);
        Date next = expression.getNextValidTimeAfter(Date.from(clock.instant()));
        plan.setNextTime(LocalDateTime.ofInstant(next.toInstant(), clock.getZone()));
        plan.setStatus("active");
        plan.setUserId(userId);
        plan.setTriggeredCount(0);
        plan.setCreatedAt(now);
        plan.setStrategyId(strategy.getId());
        plan.setExchangeId(request.exchangeId());
        plan = plans.save(plan);

        long planId = plan.getId();
        List<CoinEntity> savedCoins = request.coins().stream().map(requestCoin -> {
            CoinEntity coin = new CoinEntity();
            coin.setProportion(requestCoin.proportion().stripTrailingZeros().toPlainString());
            coin.setIcon(requestCoin.icon());
            coin.setMin(requestCoin.min());
            coin.setMax(requestCoin.max());
            coin.setAverageDown(requestCoin.averageDown());
            coin.setSymbol(requestCoin.symbol().trim().toUpperCase());
            coin.setAverage(BigDecimal.ZERO);
            coin.setTotalAmount(BigDecimal.ZERO);
            coin.setIncome(BigDecimal.ZERO);
            coin.setUserId(userId);
            coin.setCreatedAt(now);
            coin.setPlanId(planId);
            return coin;
        }).toList();
        savedCoins = coins.saveAll(savedCoins);
        scheduleAfterCommit(planId, quartzCron);
        return new StrategyCreatedData(strategy, plan, savedCoins);
    }

    @Transactional(readOnly = true)
    public List<StrategyEntity> active(long userId) {
        return strategies.findByUserId(userId);
    }

    private void scheduleAfterCommit(long planId, String cron) {
        Runnable action = () -> schedules.schedule(planId, cron);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    static String normalizeCron(String cron) {
        String normalized = cron == null ? "" : cron.trim().replaceAll("\\s+", " ");
        String[] fields = normalized.split(" ");
        if (fields.length == 5) {
            return "0 " + fields[0] + " " + fields[1] + " " + fields[2] + " " + fields[3] + " ?";
        }
        if (fields.length == 6 || fields.length == 7) return normalized;
        throw new BusinessException(400, "Cron表达式无效");
    }
}
