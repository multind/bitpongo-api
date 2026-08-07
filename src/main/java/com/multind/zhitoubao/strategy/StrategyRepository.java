package com.multind.zhitoubao.strategy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyRepository extends JpaRepository<StrategyEntity, Long> {
    Optional<StrategyEntity> findByIdAndUserId(Long id, Long userId);
    List<StrategyEntity> findByUserId(Long userId);
}
