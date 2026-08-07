package com.multind.zhitoubao;

import com.multind.zhitoubao.auth.UserRepository;
import com.multind.zhitoubao.exchange.ExchangeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ZhitoubaoApplicationTest {

    @MockitoBean private UserRepository users;
    @MockitoBean private ExchangeRepository exchanges;

    @Test
    void applicationContextStarts() {
    }
}
