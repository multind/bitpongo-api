package com.multind.zhitoubao;

import com.multind.zhitoubao.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ZhitoubaoApplicationTest {

    @MockitoBean private UserRepository users;

    @Test
    void applicationContextStarts() {
    }
}
