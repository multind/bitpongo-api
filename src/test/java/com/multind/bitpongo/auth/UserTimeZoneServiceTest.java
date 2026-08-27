package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.multind.bitpongo.auth.UserDtos.DisplayTimeZoneMode.FIXED;
import static com.multind.bitpongo.auth.UserDtos.DisplayTimeZoneMode.FOLLOW_DEVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserTimeZoneServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserEntity user = new UserEntity();
    private final UserTimeZoneService service = new UserTimeZoneService(users);

    @BeforeEach
    void setUp() {
        user.setId(7L);
        user.setDisplayTimezoneMode(FOLLOW_DEVICE.name());
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void resolvesFixedAndLatestDeviceZonesWithUtcFallback() {
        service.save(7L, FIXED, "Asia/Tokyo");
        assertThat(service.resolveDisplayZone(7L)).isEqualTo(ZoneId.of("Asia/Tokyo"));

        service.save(7L, FOLLOW_DEVICE, null);
        assertThat(service.resolveDisplayZone(7L)).isEqualTo(ZoneId.of("UTC"));

        service.syncDeviceZone(7L, "America/New_York");
        assertThat(service.resolveDisplayZone(7L)).isEqualTo(ZoneId.of("America/New_York"));
        verify(users, atLeastOnce()).save(user);
    }

    @Test
    void rejectsMissingFixedZoneOffsetsAndInvalidRegions() {
        assertThatThrownBy(() -> service.save(7L, FIXED, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.save(7L, FIXED, "+08:00"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.syncDeviceZone(7L, "Not/AZone"))
                .isInstanceOf(BusinessException.class);
    }
}
