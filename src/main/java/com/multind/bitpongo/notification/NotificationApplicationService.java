package com.multind.bitpongo.notification;

import org.springframework.stereotype.Service;

@Service
public class NotificationApplicationService {

    private final UserBarkSettingService barkSettings;

    public NotificationApplicationService(UserBarkSettingService barkSettings) {
        this.barkSettings = barkSettings;
    }

    public UserBarkSettingService.SettingView getBarkSetting(long userId) {
        return barkSettings.get(userId);
    }

    public UserBarkSettingService.SettingView updateBarkSetting(
            long userId,
            String pushUrl,
            Boolean enabled,
            String locale,
            String timezone) {
        return barkSettings.update(userId, pushUrl, enabled, locale, timezone);
    }

    public void deleteBarkSetting(long userId) {
        barkSettings.deleteForUser(userId);
    }

    public boolean testBark(long userId, String pushUrl) {
        return barkSettings.sendTest(userId, pushUrl);
    }
}
