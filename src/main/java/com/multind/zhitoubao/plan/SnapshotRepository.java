package com.multind.zhitoubao.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotRepository extends JpaRepository<SnapshotEntity, Long> {
    List<SnapshotEntity> findByPlanIdAndUserIdOrderByCreatedAtAsc(Long planId, Long userId);
}
