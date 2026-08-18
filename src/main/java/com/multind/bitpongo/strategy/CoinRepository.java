package com.multind.bitpongo.strategy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoinRepository extends JpaRepository<CoinEntity, Long> {
    Optional<CoinEntity> findByIdAndUserId(Long id, Long userId);
    List<CoinEntity> findByPlanIdAndUserId(Long planId, Long userId);
}
