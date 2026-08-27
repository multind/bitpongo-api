package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.multind.bitpongo.auth.UserDtos.DisplayTimeZoneMode.FIXED;
import static com.multind.bitpongo.auth.UserDtos.DisplayTimeZoneMode.FOLLOW_DEVICE;
import com.multind.bitpongo.auth.UserDtos.DisplayTimeZoneMode;
import com.multind.bitpongo.auth.UserDtos.TimeZonePreference;

@Service
public class UserTimeZoneService {

    private final UserRepository users;

    public UserTimeZoneService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public TimeZonePreference preference(long userId) {
        UserEntity user = user(userId);
        DisplayTimeZoneMode mode = mode(user);
        return preference(user, mode);
    }

    @Transactional
    public TimeZonePreference save(
            long userId,
            DisplayTimeZoneMode mode,
            String timezone) {
        if (mode == null) {
            throw new BusinessException(400, "显示时区模式不能为空");
        }
        UserEntity user = user(userId);
        if (mode == FIXED) {
            if (timezone == null || timezone.isBlank()) {
                throw new BusinessException(400, "固定显示时区不能为空");
            }
            user.setDisplayTimezone(regionZone(timezone).getId());
        } else {
            user.setDisplayTimezone(null);
        }
        user.setDisplayTimezoneMode(mode.name());
        users.save(user);
        return preference(user, mode);
    }

    @Transactional
    public void syncDeviceZone(long userId, String timezone) {
        UserEntity user = user(userId);
        user.setLastDeviceTimezone(regionZone(timezone).getId());
        users.save(user);
    }

    @Transactional(readOnly = true)
    public ZoneId resolveDisplayZone(long userId) {
        UserEntity user = user(userId);
        return resolve(user, mode(user));
    }

    private TimeZonePreference preference(UserEntity user, DisplayTimeZoneMode mode) {
        String fixed = mode == FIXED ? user.getDisplayTimezone() : null;
        return new TimeZonePreference(mode, fixed, resolve(user, mode).getId());
    }

    private ZoneId resolve(UserEntity user, DisplayTimeZoneMode mode) {
        if (mode == FIXED && user.getDisplayTimezone() != null) {
            return regionZone(user.getDisplayTimezone());
        }
        if (user.getLastDeviceTimezone() != null && !user.getLastDeviceTimezone().isBlank()) {
            return regionZone(user.getLastDeviceTimezone());
        }
        return ZoneId.of("UTC");
    }

    private static DisplayTimeZoneMode mode(UserEntity user) {
        try {
            return DisplayTimeZoneMode.valueOf(user.getDisplayTimezoneMode());
        } catch (RuntimeException invalidStoredMode) {
            return FOLLOW_DEVICE;
        }
    }

    private UserEntity user(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "无法验证凭据"));
    }

    private static ZoneId regionZone(String value) {
        try {
            return StrategyApplicationService.scheduleZone(value);
        } catch (BusinessException invalid) {
            throw new BusinessException(400, "显示时区必须使用有效的地区名称");
        }
    }
}
