package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserBarkSettingService {

    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final Set<String> SUPPORTED_LOCALES = Set.of("zh-CN", "zh-TW", "en-US");

    private final UserBarkSettingRepository settings;
    private final NotificationOutboxRepository outbox;
    private final BarkPushUrlParser parser;
    private final BarkCredentialCipher injectedCipher;
    private final BarkClient bark;
    private final NotificationMessageRenderer renderer;
    private final BarkProperties properties;
    private final Clock clock;

    @Autowired
    public UserBarkSettingService(
            ObjectProvider<UserBarkSettingRepository> settings,
            ObjectProvider<NotificationOutboxRepository> outbox,
            BarkClient bark,
            NotificationMessageRenderer renderer,
            BarkProperties properties) {
        this(settings.getIfAvailable(), outbox.getIfAvailable(),
                new BarkPushUrlParser(properties), null,
                bark, renderer, properties, Clock.systemUTC());
    }

    UserBarkSettingService(
            UserBarkSettingRepository settings,
            NotificationOutboxRepository outbox,
            BarkPushUrlParser parser,
            BarkCredentialCipher cipher,
            BarkClient bark,
            NotificationMessageRenderer renderer,
            BarkProperties properties,
            Clock clock) {
        this.settings = settings;
        this.outbox = outbox;
        this.parser = parser;
        this.injectedCipher = cipher;
        this.bark = bark;
        this.renderer = renderer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingView get(long userId) {
        requirePersistence();
        return settings.findByUserId(userId)
                .map(UserBarkSettingService::storedView)
                .orElseGet(SettingView::unconfigured);
    }

    @Transactional
    public SettingView update(
            long userId,
            String pushUrl,
            Boolean enabled,
            String locale,
            String timezone) {
        requirePersistence();
        Optional<UserBarkSettingEntity> existing = settings.findByUserId(userId);
        boolean replacesTarget = pushUrl != null && !pushUrl.isBlank();
        if (existing.isEmpty() && !replacesTarget) {
            throw new BusinessException(400, "Bark Push URL 不能为空");
        }

        validateLocale(locale);
        validateTimezone(timezone);

        UserBarkSettingEntity entity = existing.orElseGet(() -> newSetting(userId));
        BarkTarget target = null;
        if (replacesTarget) {
            target = parser.parse(pushUrl);
            entity.setServerUrl(serverUrl(target));
            entity.setDeviceKeyCiphertext(cipher().encrypt(target.deviceKey()));
        }
        if (enabled != null) {
            entity.setEnabled(enabled);
        }
        if (locale != null) {
            entity.setLocale(locale);
        }
        if (timezone != null) {
            entity.setTimezone(timezone);
        }

        UserBarkSettingEntity saved = settings.saveAndFlush(entity);
        if (!saved.isEnabled()) {
            outbox.skipPendingAndSendingByUserId(userId);
        }
        return target == null ? storedView(saved) : view(saved, masked(target));
    }

    @Transactional
    public void deleteForUser(long userId) {
        requirePersistence();
        settings.deleteByUserId(userId);
        outbox.skipPendingAndSendingByUserId(userId);
    }

    @Transactional(readOnly = true)
    public boolean sendTest(long userId, String pushUrl) {
        requirePersistence();
        UserBarkSettingEntity setting = null;
        BarkTarget target;
        if (pushUrl != null && !pushUrl.isBlank()) {
            target = parser.parse(pushUrl);
        } else {
            setting = settings.findByUserId(userId)
                    .orElseThrow(() -> new BusinessException(400, "Bark Push URL 尚未配置"));
            target = parser.parse(fullPushUrl(setting));
        }

        String locale = setting == null ? DEFAULT_LOCALE : setting.getLocale();
        String timezone = setting == null ? DEFAULT_TIMEZONE : setting.getTimezone();
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.BARK_TEST,
                userId,
                null,
                null,
                clock.instant(),
                null,
                Map.of());
        BarkMessage message = renderer.render(
                event, locale, timezone, blankToNull(properties.appPublicUrl()));
        bark.send(target, message);
        return true;
    }

    private BarkCredentialCipher cipher() {
        return injectedCipher == null ? new BarkCredentialCipher(properties) : injectedCipher;
    }

    private void requirePersistence() {
        if (settings == null || outbox == null) {
            throw new BusinessException(503, "Bark 通知配置暂不可用");
        }
    }

    private String fullPushUrl(UserBarkSettingEntity setting) {
        String key = URLEncoder.encode(
                        cipher().decrypt(setting.getDeviceKeyCiphertext()), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return stripTrailingSlash(setting.getServerUrl()) + "/" + key;
    }

    private static UserBarkSettingEntity newSetting(long userId) {
        UserBarkSettingEntity entity = new UserBarkSettingEntity();
        entity.setUserId(userId);
        entity.setEnabled(true);
        entity.setLocale(DEFAULT_LOCALE);
        entity.setTimezone(DEFAULT_TIMEZONE);
        return entity;
    }

    private static SettingView storedView(UserBarkSettingEntity entity) {
        return view(entity, stripTrailingSlash(entity.getServerUrl()) + "/****");
    }

    private static SettingView view(UserBarkSettingEntity entity, String maskedPushUrl) {
        return new SettingView(
                true,
                entity.isEnabled(),
                maskedPushUrl,
                entity.getLocale(),
                entity.getTimezone(),
                entity.getUpdatedAt());
    }

    private static String masked(BarkTarget target) {
        String deviceKey = target.deviceKey();
        String suffix = deviceKey.length() <= 4
                ? ""
                : deviceKey.substring(deviceKey.length() - 4);
        return serverUrl(target) + "/****" + suffix;
    }

    private static String serverUrl(BarkTarget target) {
        return stripTrailingSlash(target.serverUrl().toASCIIString());
    }

    private static String stripTrailingSlash(String value) {
        String stripped = value;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static void validateLocale(String locale) {
        if (locale != null && !SUPPORTED_LOCALES.contains(locale)) {
            throw new BusinessException(400, "Bark locale 无效");
        }
    }

    private static void validateTimezone(String timezone) {
        if (timezone == null) {
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new BusinessException(400, "Bark timezone 无效");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record SettingView(
            boolean configured,
            boolean enabled,
            String maskedPushUrl,
            String locale,
            String timezone,
            LocalDateTime updatedAt) {

        static SettingView unconfigured() {
            return new SettingView(
                    false, false, null, DEFAULT_LOCALE, DEFAULT_TIMEZONE, null);
        }
    }
}
