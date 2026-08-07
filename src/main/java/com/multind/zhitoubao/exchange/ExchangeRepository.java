package com.multind.zhitoubao.exchange;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRepository extends JpaRepository<ExchangeEntity, Long> {
    Optional<ExchangeEntity> findByIdAndUserId(Long id, Long userId);
    List<ExchangeEntity> findByUserId(Long userId);
}
