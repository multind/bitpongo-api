package com.multind.bitpongo.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntity, Long> {
    Optional<NotificationOutboxEntity> findByDedupeKey(String dedupeKey);
    boolean existsByDedupeKey(String dedupeKey);
}
