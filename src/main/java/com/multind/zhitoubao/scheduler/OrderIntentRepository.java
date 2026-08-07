package com.multind.zhitoubao.scheduler;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderIntentRepository extends JpaRepository<OrderIntentEntity, Long> {
    Optional<OrderIntentEntity> findByClientOrderId(String clientOrderId);
    List<OrderIntentEntity> findByStatusOrderByCreatedAtAsc(String status);
}
