package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.EmailCodeSender;
import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEscalationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmailCodeSender email = mock(EmailCodeSender.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
    private final BarkProperties properties = new BarkProperties(
            true, null, Set.of(), false, null, true, false, "https://app.example/");
    private NotificationEscalationService service;

    @BeforeEach
    void setup() {
        service = new NotificationEscalationService(jdbc, email, properties, clock, transactions);
    }

    @Test
    void schedulesAConfirmationDeadlineWhenEnabled() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(7L))).thenReturn(true);

        service.schedule(7L, "price-alert:1:1", Instant.parse("2026-10-25T00:00:00Z"));

        verify(jdbc).update(
                contains("insert into notification_escalation"),
                eq("price-alert:1:1"),
                eq(7L),
                eq(LocalDateTime.of(2026, 10, 25, 0, 5)),
                eq(LocalDateTime.of(2026, 10, 25, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 25, 0, 0)));
    }

    @Test
    void doesNotScheduleWhenConfirmationIsDisabled() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(7L))).thenReturn(false);

        service.schedule(7L, "price-alert:1:1", Instant.parse("2026-10-25T00:00:00Z"));

        verify(jdbc, never()).update(contains("insert into notification_escalation"), any(), any(), any(), any(), any());
    }

    @Test
    void confirmingAnAlertCancelsTheEscalation() {
        when(jdbc.queryForList("select event_key from alert_event where id=? and user_id=?", 9L, 7L))
                .thenReturn(List.of(Map.of("event_key", "price-alert:1:1")));

        service.confirm(7L, 9L);

        verify(jdbc).update(
                contains("update alert_event"),
                eq(LocalDateTime.of(2026, 10, 25, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 25, 0, 0)),
                eq(9L),
                eq(7L));
        verify(jdbc).update(
                contains("update notification_escalation"),
                eq(LocalDateTime.of(2026, 10, 25, 0, 0)),
                eq("price-alert:1:1"),
                eq(7L));
    }

    @Test
    void rejectsConfirmingAnotherUsersAlert() {
        when(jdbc.queryForList("select event_key from alert_event where id=? and user_id=?", 9L, 7L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.confirm(7L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("提醒不存在");
    }
}
