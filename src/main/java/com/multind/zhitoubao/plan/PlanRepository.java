package com.multind.zhitoubao.plan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanRepository extends JpaRepository<PlanEntity, Long> {
    Optional<PlanEntity> findByIdAndUserId(Long id, Long userId);
    List<PlanEntity> findByUserIdAndStatusNot(Long userId, String status);
    List<PlanEntity> findByUserId(Long userId);
    List<PlanEntity> findByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select plan from PlanEntity plan where plan.userId = :userId")
    List<PlanEntity> findAllForAccountDeletion(@Param("userId") Long userId);
}
