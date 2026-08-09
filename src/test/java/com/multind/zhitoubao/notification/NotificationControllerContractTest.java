package com.multind.zhitoubao.notification;

import com.multind.zhitoubao.auth.JwtTokenService;
import com.multind.zhitoubao.auth.UserRepository;
import com.multind.zhitoubao.exchange.ExchangeApplicationService;
import com.multind.zhitoubao.plan.PlanApplicationService;
import com.multind.zhitoubao.strategy.StrategyApplicationService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc
class NotificationControllerContractTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean NotificationApplicationService notifications;
    @MockitoBean ExchangeApplicationService exchangeApplicationService;
    @MockitoBean StrategyApplicationService strategyApplicationService;
    @MockitoBean PlanApplicationService planApplicationService;
    @MockitoBean UserRepository users;

    @Test
    void noticesAndDingKeepCurrentContractAndCopy() throws Exception {
        when(notifications.notices()).thenReturn("邮件,钉钉");
        when(notifications.testDing(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("errcode", 0));
        mvc.perform(get("/api/users/notices").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("邮件,钉钉"));
        mvc.perform(post("/api/users/ding").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhook\":\"https://example.test/hook\",\"signed\":\"secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.errcode").value(0));
        verify(notifications).testDing(org.mockito.ArgumentMatchers.eq("https://example.test/hook"),
                org.mockito.ArgumentMatchers.eq("secret"));
    }

    private String bearer() { return "Bearer " + tokens.issue(7L); }
}
