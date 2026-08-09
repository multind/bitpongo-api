package com.multind.zhitoubao.scheduler;

import com.multind.zhitoubao.plan.PlanRepository;
import com.multind.zhitoubao.strategy.StrategyApplicationService;
import com.multind.zhitoubao.strategy.StrategyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(PlanScheduleService.class)
public class ScheduleReconciler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleReconciler.class);
    private final PlanRepository plans;
    private final StrategyRepository strategies;
    private final PlanScheduleService schedules;

    public ScheduleReconciler(
            PlanRepository plans, StrategyRepository strategies, PlanScheduleService schedules) {
        this.plans = plans;
        this.strategies = strategies;
        this.schedules = schedules;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        plans.findAll().forEach(plan -> {
            try {
                if (!"active".equals(plan.getStatus())) {
                    schedules.remove(plan.getId());
                    return;
                }
                strategies.findById(plan.getStrategyId()).ifPresentOrElse(
                        strategy -> schedules.schedule(plan.getId(),
                                StrategyApplicationService.normalizeCron(strategy.getCron())),
                        () -> log.warn("计划 {} 缺少策略，跳过任务恢复", plan.getId()));
            } catch (RuntimeException exception) {
                log.error("核对计划任务失败，planId={}", plan.getId(), exception);
            }
        });
        if (schedules instanceof QuartzPlanScheduleService quartz) quartz.scheduleAssetSnapshot();
    }
}
