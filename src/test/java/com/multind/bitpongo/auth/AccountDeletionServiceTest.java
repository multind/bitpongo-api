package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.scheduler.PlanScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountDeletionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 7, 30);

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordCompatibilityService passwords = mock(PasswordCompatibilityService.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final ExchangeRepository exchanges = mock(ExchangeRepository.class);
    private final PlanScheduleService schedules = mock(PlanScheduleService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-14T07:30:00Z"), ZoneOffset.UTC);
    private AccountDeletionService service;

    @BeforeEach
    void setUp() {
        service = new AccountDeletionService(
                users, passwords, plans, exchanges, schedules, clock);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void wrongPasswordLeavesAccountAndTradingDataUnchanged() {
        UserEntity user = activeUser();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(passwords.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.delete(7L, "wrong"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(401);
                    assertThat(error.getMessage()).isEqualTo("密码错误");
                });

        verify(users, never()).save(any());
        verifyNoInteractions(plans, exchanges, schedules);
        assertThat(user.getStatus()).isEqualTo("active");
    }

    @Test
    void deletionStopsTradingClearsSecretsAndAnonymizesWithoutExternalIdentity() {
        UserEntity user = activeUser();
        PlanEntity plan = new PlanEntity();
        plan.setId(11L);
        plan.setStatus("active");
        ExchangeEntity exchange = new ExchangeEntity();
        exchange.setAccessKey("access-key");
        exchange.setSecretKey("secret-key");
        exchange.setPassword("passphrase");
        exchange.setStatus("active");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(passwords.matches("secret", "stored-hash")).thenReturn(true);
        when(passwords.hash(argThat(value -> value != null && value.length() == 64)))
                .thenReturn("anonymized-hash");
        when(plans.findAllForAccountDeletion(7L)).thenReturn(List.of(plan));
        when(exchanges.findAllForAccountDeletion(7L)).thenReturn(List.of(exchange));
        TransactionSynchronizationManager.initSynchronization();

        service.delete(7L, "secret");

        assertThat(user.getStatus()).isEqualTo("deleted");
        assertThat(user.getDeletedAt()).isEqualTo(NOW);
        assertThat(user.getName()).isEqualTo("已注销用户");
        assertThat(user.getEmail()).startsWith("deleted+7+").endsWith("@invalid.local");
        assertThat(user.getPassword()).isEqualTo("anonymized-hash");
        assertThat(plan.getStatus()).isEqualTo("stop");
        assertThat(exchange.getAccessKey()).isNull();
        assertThat(exchange.getSecretKey()).isNull();
        assertThat(exchange.getPassword()).isNull();
        assertThat(exchange.getStatus()).isEqualTo("deleted");
        verify(schedules, never()).pause(anyLong());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.getFirst().afterCommit();
        verify(schedules).pause(11L);
    }

    @Test
    void schedulerFailureAfterCommitDoesNotEscapeOrRestoreAccount() {
        UserEntity user = activeUser();
        PlanEntity first = plan(11L);
        PlanEntity second = plan(12L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(passwords.matches("secret", "stored-hash")).thenReturn(true);
        when(passwords.hash(any())).thenReturn("anonymized-hash");
        when(plans.findAllForAccountDeletion(7L)).thenReturn(List.of(first, second));
        when(exchanges.findAllForAccountDeletion(7L)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("scheduler unavailable"))
                .when(schedules).pause(11L);
        TransactionSynchronizationManager.initSynchronization();

        service.delete(7L, "secret");
        TransactionSynchronization callback =
                TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatCode(callback::afterCommit).doesNotThrowAnyException();
        verify(schedules).pause(11L);
        verify(schedules).pause(12L);
        assertThat(user.getStatus()).isEqualTo("deleted");
    }

    private static UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setName("原用户");
        user.setEmail("old@example.com");
        user.setPassword("stored-hash");
        user.setStatus("active");
        return user;
    }

    private static PlanEntity plan(long id) {
        PlanEntity plan = new PlanEntity();
        plan.setId(id);
        plan.setStatus("active");
        return plan;
    }
}
