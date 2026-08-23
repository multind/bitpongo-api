package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserBarkSettingServiceTest {

    private static final String TEST_ENCRYPTION_KEY =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 23, 12, 0);

    private final UserBarkSettingRepository settings = mock(UserBarkSettingRepository.class);
    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final RecordingBarkClient bark = new RecordingBarkClient();
    private final BarkCredentialCipher cipher = new BarkCredentialCipher(TEST_ENCRYPTION_KEY);
    private UserBarkSettingService service;

    @BeforeEach
    void setUp() throws Exception {
        BarkProperties properties = new BarkProperties(
                true, "", Set.of("api.day.app"), false,
                TEST_ENCRYPTION_KEY, true, false, "https://app.example.test");
        BarkPushUrlParser parser = new BarkPushUrlParser(
                Set.of("api.day.app"), false,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T04:00:00Z"), ZoneOffset.UTC);
        service = new UserBarkSettingService(
                settings, outbox, parser, cipher, bark,
                new NotificationMessageRenderer(new BarkEventPolicy()), properties, clock);
    }

    @Test
    void updateEncryptsDeviceKeyAndReturnsOnlyMaskedUrl() {
        when(settings.findByUserId(7L)).thenReturn(Optional.empty());
        when(settings.saveAndFlush(any())).thenAnswer(invocation -> {
            UserBarkSettingEntity entity = invocation.getArgument(0);
            entity.setUpdatedAt(UPDATED_AT);
            return entity;
        });

        UserBarkSettingService.SettingView result = service.update(
                7L, "https://api.day.app/fake-device-key/test?call=1",
                true, "zh-CN", "Asia/Shanghai");

        ArgumentCaptor<UserBarkSettingEntity> saved =
                ArgumentCaptor.forClass(UserBarkSettingEntity.class);
        verify(settings).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getServerUrl()).isEqualTo("https://api.day.app");
        assertThat(saved.getValue().getDeviceKeyCiphertext())
                .startsWith("v1:")
                .doesNotContain("fake-device-key");
        assertThat(cipher.decrypt(saved.getValue().getDeviceKeyCiphertext()))
                .isEqualTo("fake-device-key");
        assertThat(result.maskedPushUrl()).isEqualTo("https://api.day.app/****-key");
        assertThat(result.toString()).doesNotContain("fake-device-key");
    }

    @Test
    void getNeverDecryptsOrReturnsAnyPartOfStoredCiphertext() {
        UserBarkSettingEntity entity = setting("not-a-valid-envelope-with-fake-device-key");
        when(settings.findByUserId(7L)).thenReturn(Optional.of(entity));

        UserBarkSettingService.SettingView result = service.get(7L);

        assertThat(result.configured()).isTrue();
        assertThat(result.maskedPushUrl()).isEqualTo("https://api.day.app/****");
        assertThat(result.toString())
                .doesNotContain("fake-device-key")
                .doesNotContain("not-a-valid-envelope");
    }

    @Test
    void blankPushUrlUpdatesExistingPreferencesWithoutReplacingTarget() {
        UserBarkSettingEntity entity = setting(cipher.encrypt("fake-existing-key"));
        when(settings.findByUserId(7L)).thenReturn(Optional.of(entity));
        when(settings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(7L, " ", false, "en-US", "America/New_York");

        ArgumentCaptor<UserBarkSettingEntity> saved =
                ArgumentCaptor.forClass(UserBarkSettingEntity.class);
        verify(settings).saveAndFlush(saved.capture());
        assertThat(cipher.decrypt(saved.getValue().getDeviceKeyCiphertext()))
                .isEqualTo("fake-existing-key");
        assertThat(saved.getValue().isEnabled()).isFalse();
        assertThat(saved.getValue().getLocale()).isEqualTo("en-US");
        assertThat(saved.getValue().getTimezone()).isEqualTo("America/New_York");
        verify(outbox).skipPendingAndSendingByUserId(7L);
    }

    @Test
    void creationRequiresPushUrlAndPreferencesAreValidated() {
        when(settings.findByUserId(7L)).thenReturn(Optional.empty());

        assertBadRequest(() -> service.update(7L, null, true, "zh-CN", "Asia/Shanghai"));
        assertBadRequest(() -> service.update(
                7L, "https://api.day.app/fake-device-key", true, "fr-FR", "Asia/Shanghai"));
        assertBadRequest(() -> service.update(
                7L, "https://api.day.app/fake-device-key", true, "zh-CN", "Mars/Olympus"));

        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    void deleteRemovesSettingAndSkipsPendingAndSendingNotifications() {
        service.deleteForUser(7L);

        verify(settings).deleteByUserId(7L);
        verify(outbox).skipPendingAndSendingByUserId(7L);
    }

    @Test
    void temporaryTestUrlUsesBarkTestPolicyWithoutPersistingQueryOverrides() {
        boolean sent = service.sendTest(
                7L, "https://api.day.app/temporary-fake-key?call=1&sound=alarm");

        assertThat(sent).isTrue();
        assertThat(bark.target.deviceKey()).isEqualTo("temporary-fake-key");
        assertThat(bark.message.level()).isEqualTo("active");
        assertThat(bark.message.call()).isFalse();
        assertThat(bark.message.volume()).isNull();
        assertThat(bark.message.sound()).isEqualTo("minuet");
        assertThat(bark.message.group()).isEqualTo("Bitpongo·测试");
        assertThat(bark.message.url()).isEqualTo("https://app.example.test");
        verifyNoInteractions(settings);
    }

    @Test
    void testWithoutTemporaryUrlUsesSavedEncryptedTargetWithoutSaving() {
        UserBarkSettingEntity entity = setting(cipher.encrypt("saved-fake-key"));
        entity.setLocale("zh-TW");
        entity.setTimezone("Asia/Taipei");
        when(settings.findByUserId(7L)).thenReturn(Optional.of(entity));

        assertThat(service.sendTest(7L, null)).isTrue();

        assertThat(bark.target.deviceKey()).isEqualTo("saved-fake-key");
        assertThat(bark.message.title()).isEqualTo("Bark 測試");
        verify(settings, never()).saveAndFlush(any());
    }

    private static UserBarkSettingEntity setting(String ciphertext) {
        UserBarkSettingEntity entity = new UserBarkSettingEntity();
        entity.setUserId(7L);
        entity.setServerUrl("https://api.day.app");
        entity.setDeviceKeyCiphertext(ciphertext);
        entity.setEnabled(true);
        entity.setLocale("zh-CN");
        entity.setTimezone("Asia/Shanghai");
        entity.setUpdatedAt(UPDATED_AT);
        return entity;
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(400));
    }

    private static final class RecordingBarkClient implements BarkClient {
        private BarkTarget target;
        private BarkMessage message;

        @Override
        public void send(BarkTarget target, BarkMessage message) {
            this.target = target;
            this.message = message;
        }
    }
}
