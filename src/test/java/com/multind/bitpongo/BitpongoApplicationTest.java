package com.multind.bitpongo;

import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.plan.PlanApplicationService;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class BitpongoApplicationTest {

    @MockitoBean private UserRepository users;
    @MockitoBean private com.multind.bitpongo.auth.DeletedExternalIdentityRepository deletedExternalIdentities;
    @MockitoBean private com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;
    @MockitoBean private ExchangeRepository exchanges;
    @MockitoBean private PlanApplicationService planApplicationService;
    @MockitoBean private StrategyApplicationService strategyApplicationService;

    @Test
    void applicationContextStarts() {
    }
}
