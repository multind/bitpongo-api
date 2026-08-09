package com.multind.zhitoubao;

import com.multind.zhitoubao.auth.UserRepository;
import com.multind.zhitoubao.exchange.ExchangeRepository;
import com.multind.zhitoubao.plan.PlanApplicationService;
import com.multind.zhitoubao.strategy.StrategyApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ZhitoubaoApplicationTest {

    @MockitoBean private UserRepository users;
    @MockitoBean private ExchangeRepository exchanges;
    @MockitoBean private PlanApplicationService planApplicationService;
    @MockitoBean private StrategyApplicationService strategyApplicationService;

    @Test
    void applicationContextStarts() {
    }
}
