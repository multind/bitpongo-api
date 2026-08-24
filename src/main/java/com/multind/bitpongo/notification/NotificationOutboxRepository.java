package com.multind.bitpongo.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntity, Long> {
    Optional<NotificationOutboxEntity> findByDedupeKey(String dedupeKey);
    boolean existsByDedupeKey(String dedupeKey);

    @Modifying(flushAutomatically = true)
    @Query("""
            update NotificationOutboxEntity message
               set message.status = com.multind.bitpongo.notification.NotificationOutboxStatus.SKIPPED,
                   message.leaseUntil = null,
                   message.leaseToken = null,
                   message.updatedAt = current_timestamp
             where message.userId = :userId
               and message.status in (
                   com.multind.bitpongo.notification.NotificationOutboxStatus.PENDING,
                   com.multind.bitpongo.notification.NotificationOutboxStatus.SENDING)
            """)
    int skipPendingAndSendingByUserId(@Param("userId") long userId);
}
