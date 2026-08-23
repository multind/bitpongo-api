package com.multind.bitpongo.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties("zhitoubao.notifications.bark")
public record BarkProperties(
        boolean userNotificationsEnabled,
        String adminPushUrl,
        Set<String> allowedHosts,
        boolean allowPrivateHosts,
        String credentialEncryptionKey,
        boolean notifyOnStartup,
        String appPublicUrl) {

    @Override
    public String toString() {
        return "BarkProperties["
                + "userNotificationsEnabled=" + userNotificationsEnabled
                + ", adminPushUrl=<redacted>"
                + ", allowedHosts=" + allowedHosts
                + ", allowPrivateHosts=" + allowPrivateHosts
                + ", credentialEncryptionKey=<redacted>"
                + ", notifyOnStartup=" + notifyOnStartup
                + ", appPublicUrl=" + appPublicUrl
                + ']';
    }
}
