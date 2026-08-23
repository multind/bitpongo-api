package com.multind.bitpongo.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBarkSettingRepository extends JpaRepository<UserBarkSettingEntity, Long> {
    Optional<UserBarkSettingEntity> findByUserId(long userId);
}
