package com.multind.bitpongo.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotRepository extends JpaRepository<SnapshotEntity, Long> {
    List<SnapshotEntity> findByPlanIdAndUserIdOrderByCreatedAtDesc(Long planId, Long userId, org.springframework.data.domain.Pageable pageable);
    List<SnapshotEntity> findByPlanIdAndUserIdOrderByCreatedAtAsc(Long planId, Long userId);
}
