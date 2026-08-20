package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import com.multind.bitpongo.strategy.StrategyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ScheduleReconciler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleReconciler.class);
    private final ObjectProvider<PlanRepository> plans;
    private final ObjectProvider<StrategyRepository> strategies;
    private final ObjectProvider<PlanScheduleService> schedules;

    public ScheduleReconciler(
            ObjectProvider<PlanRepository> plans,
            ObjectProvider<StrategyRepository> strategies,
            ObjectProvider<PlanScheduleService> schedules) {
        this.plans = plans;
        this.strategies = strategies;
        this.schedules = schedules;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        PlanRepository planRepository = plans.getIfAvailable();
        StrategyRepository strategyRepository = strategies.getIfAvailable();
        PlanScheduleService scheduleService = schedules.getIfAvailable();
        if (planRepository == null || strategyRepository == null || scheduleService == null) {
            log.warn("计划任务核对依赖缺失，跳过恢复");
            return;
        }
        log.info("开始核对计划任务，共 {} 个计划", planRepository.count());
        planRepository.findAll().forEach(plan -> {
            try {
                if (!"active".equals(plan.getStatus())) {
                    scheduleService.remove(plan.getId());
                    return;
                }
                strategyRepository.findById(plan.getStrategyId()).ifPresentOrElse(
                        strategy -> scheduleService.schedule(plan.getId(),
                                StrategyApplicationService.normalizeCron(strategy.getCron())),
                        () -> log.warn("计划 {} 缺少策略，跳过任务恢复", plan.getId()));
            } catch (RuntimeException exception) {
                log.error("核对计划任务失败，planId={}", plan.getId(), exception);
            }
        });
        if (scheduleService instanceof QuartzPlanScheduleService quartz) {
            try {
                quartz.scheduleAssetSnapshot();
            } catch (RuntimeException exception) {
                log.warn("资产快照任务注册失败，将在下次启动时重试: {}", exception.getMessage());
            }
        }
    }
}
