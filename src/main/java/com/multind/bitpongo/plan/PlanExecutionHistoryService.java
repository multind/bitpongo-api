package com.multind.bitpongo.plan;

import com.multind.bitpongo.common.api.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanExecutionHistoryService {
    private final PlanRepository plans;
    private final PlanExecutionRepository executions;

    @Autowired
    public PlanExecutionHistoryService(
            ObjectProvider<PlanRepository> plans,
            ObjectProvider<PlanExecutionRepository> executions) {
        this(plans.getIfAvailable(), executions.getIfAvailable());
    }

    PlanExecutionHistoryService(PlanRepository plans, PlanExecutionRepository executions) {
        this.plans = plans;
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public PlanDtos.ExecutionPage list(long userId, long planId, int page, int size) {
        if (plans == null || executions == null) {
            throw new IllegalStateException("计划执行历史服务不可用");
        }
        if (page < 0) throw new BusinessException(400, "页码不能小于 0");
        plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "交易计划不存在"));
        int pageSize = Math.max(1, Math.min(size, 50));
        var result = executions.findByPlanId(
                Math.toIntExact(planId),
                PageRequest.of(page, pageSize,
                        Sort.by(Sort.Order.desc("scheduledFireTime"), Sort.Order.desc("id"))));
        return new PlanDtos.ExecutionPage(
                result.getContent().stream().map(PlanDtos.ExecutionView::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.hasNext());
    }
}
