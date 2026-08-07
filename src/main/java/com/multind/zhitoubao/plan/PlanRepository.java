package com.multind.zhitoubao.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<PlanEntity, Long> {
    Optional<PlanEntity> findByIdAndUserId(Long id, Long userId);
    List<PlanEntity> findByUserIdAndStatusNot(Long userId, String status);
    List<PlanEntity> findByUserId(Long userId);
}
