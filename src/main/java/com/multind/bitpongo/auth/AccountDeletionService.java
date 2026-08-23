package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.scheduler.PlanScheduleService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AccountDeletionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountDeletionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final UserRepository users;
    private final PasswordCompatibilityService passwords;
    private final PlanRepository plans;
    private final ExchangeRepository exchanges;
    private final PlanScheduleService schedules;
    private final Clock clock;

    @Autowired
    public AccountDeletionService(
            UserRepository users,
            PasswordCompatibilityService passwords,
            PlanRepository plans,
            ExchangeRepository exchanges,
            ObjectProvider<PlanScheduleService> schedules) {
        this(users, passwords, plans, exchanges,
                schedules.getIfAvailable(), Clock.systemUTC());
    }

    AccountDeletionService(
            UserRepository users,
            PasswordCompatibilityService passwords,
            PlanRepository plans,
            ExchangeRepository exchanges,
            PlanScheduleService schedules,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.plans = plans;
        this.exchanges = exchanges;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Transactional
    public void delete(long userId, String password) {
        UserEntity user = users.findById(userId)
                .filter(UserEntity::isActive)
                .orElseThrow(() -> new BusinessException(401, "账号不可用"));
        if (!passwords.matches(password, user.getPassword())) {
            throw new BusinessException(400, "密码错误");
        }

        List<PlanEntity> userPlans = plans.findAllForAccountDeletion(userId);
        List<ExchangeEntity> userExchanges = exchanges.findAllForAccountDeletion(userId);
        List<Long> planIds = userPlans.stream().map(PlanEntity::getId).toList();

        userPlans.forEach(plan -> plan.setStatus("stop"));
        userExchanges.forEach(AccountDeletionService::clearCredentials);

        LocalDateTime now = LocalDateTime.now(clock);
        anonymize(user, userId, now);
        pausePlansAfterCommit(planIds);
    }

    private void anonymize(UserEntity user, long userId, LocalDateTime now) {
        user.setName("已注销用户");
        user.setEmail("deleted+" + userId + "+" + UUID.randomUUID() + "@invalid.local");
        user.setPassword(passwords.hash(randomPassword()));
        user.setStatus("deleted");
        user.setDeletedAt(now);
    }

    private void pausePlansAfterCommit(List<Long> planIds) {
        if (schedules == null) {
            LOGGER.warn("account deletion plan scheduler is unavailable");
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOGGER.error("account deletion transaction synchronization is unavailable");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long planId : planIds) {
                    try {
                        schedules.pause(planId);
                    } catch (RuntimeException exception) {
                        LOGGER.error("failed to pause deleted account plan planId={}", planId, exception);
                    }
                }
            }
        });
    }

    private static void clearCredentials(ExchangeEntity exchange) {
        exchange.setAccessKey(null);
        exchange.setSecretKey(null);
        exchange.setPassword(null);
        exchange.setStatus("deleted");
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
