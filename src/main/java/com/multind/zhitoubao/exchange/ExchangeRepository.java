package com.multind.zhitoubao.exchange;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExchangeRepository extends JpaRepository<ExchangeEntity, Long> {
    Optional<ExchangeEntity> findByIdAndUserId(Long id, Long userId);
    List<ExchangeEntity> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select exchange from ExchangeEntity exchange where exchange.userId = :userId")
    List<ExchangeEntity> findAllForAccountDeletion(@Param("userId") Long userId);
}
