package com.multind.bitpongo.plan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanExecutionRepository extends JpaRepository<PlanExecutionEntity, Long> {
    Page<PlanExecutionEntity> findByPlanId(Integer planId, Pageable pageable);
}
