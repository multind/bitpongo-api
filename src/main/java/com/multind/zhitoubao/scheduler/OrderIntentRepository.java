package com.multind.zhitoubao.scheduler;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

public interface OrderIntentRepository extends JpaRepository<OrderIntentEntity, Long> {
    Optional<OrderIntentEntity> findByClientOrderId(String clientOrderId);
    List<OrderIntentEntity> findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(
            Collection<String> statuses, LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query("""
            update OrderIntentEntity intent
               set intent.status = 'RECONCILING', intent.updatedAt = :now
             where intent.id = :id
               and intent.status in :statuses
               and intent.updatedAt <= :cutoff
            """)
    int acquireForReconciliation(
            @Param("id") long id,
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from OrderIntentEntity intent where intent.id = :id")
    Optional<OrderIntentEntity> findByIdForUpdate(@Param("id") long id);
}
