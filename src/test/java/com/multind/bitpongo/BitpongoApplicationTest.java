package com.multind.bitpongo;

import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.notification.NotificationPublisher;
import com.multind.bitpongo.plan.AssetSnapshotService;
import com.multind.bitpongo.plan.PlanApplicationService;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.plan.OrderRepository;
import com.multind.bitpongo.plan.SnapshotRepository;
import com.multind.bitpongo.scheduler.AssetSnapshotUseCase;
import com.multind.bitpongo.scheduler.OrderIntentRepository;
import com.multind.bitpongo.scheduler.OrderPersistenceService;
import com.multind.bitpongo.scheduler.OrderReconciliationService;
import com.multind.bitpongo.scheduler.ScheduledPurchaseService;
import com.multind.bitpongo.scheduler.ScheduledPurchaseUseCase;
import com.multind.bitpongo.strategy.CoinRepository;
import com.multind.bitpongo.strategy.StrategyRepository;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BitpongoApplicationTest {

    @Autowired private ApplicationContext context;
    @MockitoBean private PlanRepository plans;
    @MockitoBean private SnapshotRepository snapshots;
    @MockitoBean private StrategyRepository strategies;
    @MockitoBean private CoinRepository coins;
    @MockitoBean private OrderRepository orders;
    @MockitoBean private OrderIntentRepository intents;
    @MockitoBean private JdbcTemplate jdbc;
    @MockitoBean private NotificationPublisher notifications;

    @MockitoBean private UserRepository users;
    @MockitoBean private com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;
    @MockitoBean private ExchangeRepository exchanges;
    @MockitoBean private PlanApplicationService planApplicationService;
    @MockitoBean private StrategyApplicationService strategyApplicationService;

    @Test
    void applicationContextStarts() {
    }

    @Test
    void schedulerServicesAreRegistered() {
        assertThat(context.getBean(ScheduledPurchaseUseCase.class))
                .isInstanceOf(ScheduledPurchaseService.class);
        assertThat(context.getBean(AssetSnapshotUseCase.class))
                .isInstanceOf(AssetSnapshotService.class);
        assertThat(context.getBean(OrderPersistenceService.class)).isNotNull();
        assertThat(context.getBean(OrderReconciliationService.class)).isNotNull();
    }
}
