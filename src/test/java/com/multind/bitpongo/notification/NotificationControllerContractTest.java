package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.JwtTokenService;
import com.multind.bitpongo.auth.UserEntity;
import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeApplicationService;
import com.multind.bitpongo.plan.PlanApplicationService;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@MockitoBean(types = {
        com.multind.bitpongo.plan.AssetSnapshotService.class,
        com.multind.bitpongo.scheduler.OrderPersistenceService.class,
        com.multind.bitpongo.scheduler.OrderReconciliationService.class,
        com.multind.bitpongo.scheduler.ScheduledPurchaseService.class
})
class NotificationControllerContractTest {

    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 23, 12, 0);

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService tokens;

    @MockitoBean private NotificationApplicationService notifications;
    @MockitoBean private ExchangeApplicationService exchangeApplicationService;
    @MockitoBean private StrategyApplicationService strategyApplicationService;
    @MockitoBean private PlanApplicationService planApplicationService;
    @MockitoBean private UserRepository users;
    @MockitoBean private com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;

    @BeforeEach
    void authenticateUserSeven() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("active");
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void authenticatedUserCanCreateBarkSettingWithoutExposingDeviceKey() throws Exception {
        when(notifications.updateBarkSetting(
                eq(7L), any(), eq(true), eq("zh-CN"), eq("Asia/Shanghai")))
                .thenReturn(new UserBarkSettingService.SettingView(
                        true, true, "https://api.day.app/****-key",
                        "zh-CN", "Asia/Shanghai", UPDATED_AT));

        mvc.perform(put("/api/users/notifications/bark")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"push_url":"https://api.day.app/fake-device-key/test?call=1",
                                 "enabled":true,"locale":"zh-CN","timezone":"Asia/Shanghai"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.masked_push_url").value("https://api.day.app/****-key"))
                .andExpect(jsonPath("$.data.push_url").doesNotExist())
                .andExpect(jsonPath("$.data.device_key").doesNotExist());

        verify(notifications).updateBarkSetting(
                7L, "https://api.day.app/fake-device-key/test?call=1",
                true, "zh-CN", "Asia/Shanghai");
    }

    @Test
    void getUsesAuthenticatedUserAndReturnsUnconfiguredInsteadOfNotFound() throws Exception {
        when(notifications.getBarkSetting(7L))
                .thenReturn(new UserBarkSettingService.SettingView(
                        false, false, null, "zh-CN", "Asia/Shanghai", null));

        mvc.perform(get("/api/users/notifications/bark").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.masked_push_url").doesNotExist())
                .andExpect(jsonPath("$.data.device_key_ciphertext").doesNotExist());

        verify(notifications).getBarkSetting(7L);
    }

    @Test
    void deleteAndTestAreScopedToAuthenticatedUser() throws Exception {
        when(notifications.testBark(7L, "https://api.day.app/temporary-fake-key?call=1"))
                .thenReturn(true);

        mvc.perform(post("/api/users/notifications/bark/test")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"push_url":"https://api.day.app/temporary-fake-key?call=1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true));

        mvc.perform(delete("/api/users/notifications/bark").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notifications).testBark(7L, "https://api.day.app/temporary-fake-key?call=1");
        verify(notifications).deleteBarkSetting(7L);
    }

    @Test
    void barkRoutesRequireAuthenticationAndRejectBodyUserId() throws Exception {
        mvc.perform(get("/api/users/notifications/bark"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/users/notifications/bark")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id":8,"push_url":"https://api.day.app/fake-device-key"}
                                """))
                .andExpect(status().isBadRequest());

        verify(notifications, never()).updateBarkSetting(anyLong(), any(), any(), any(), any());
    }

    @Test
    void removedDingTalkAndNoticeRoutesReturnNotFound() throws Exception {
        mvc.perform(get("/api/users/notices").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/users/ding")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private String bearer() {
        return "Bearer " + tokens.issue(7L);
    }
}
