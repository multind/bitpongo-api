package com.multind.zhitoubao.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByIdAndUserId(Long id, Long userId);
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);
    List<OrderEntity> findByPlanIdAndUserId(Long planId, Long userId);
}
