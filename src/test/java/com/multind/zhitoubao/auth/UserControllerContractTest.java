package com.multind.zhitoubao.auth;

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerContractTest {

    @Autowired private MockMvc mvc;
    @Autowired private PasswordCompatibilityService passwords;
    @Autowired private JwtTokenService tokens;

    @MockitoBean private UserRepository users;
    @MockitoBean private WordPressAuthClient wordpress;
    @MockitoBean private com.multind.zhitoubao.exchange.ExchangeApplicationService exchangeApplicationService;
    @MockitoBean private com.multind.zhitoubao.plan.PlanApplicationService planApplicationService;
    @MockitoBean private com.multind.zhitoubao.strategy.StrategyApplicationService strategyApplicationService;

    private UserEntity localUser;

    @BeforeEach
    void setUp() {
        localUser = new UserEntity();
        localUser.setId(7L);
        localUser.setName("测试用户");
        localUser.setEmail("local@example.com");
        localUser.setPassword(passwords.hash("secret"));
        localUser.setCreatedAt(LocalDateTime.of(2025, 1, 2, 3, 4));
        when(users.findByEmail("local@example.com")).thenReturn(Optional.of(localUser));
        when(users.findById(7L)).thenReturn(Optional.of(localUser));
        when(users.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(9L);
            }
            return saved;
        });
    }

    @Test
    void localLoginAndProfileKeepExistingPayloads() throws Exception {
        mvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"local@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.info.id").value(7))
                .andExpect(jsonPath("$.data.info.email").value("local@example.com"))
                .andExpect(jsonPath("$.data.token").isString());

        mvc.perform(get("/api/users/profile")
                        .header("Authorization", "Bearer " + tokens.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("测试用户"))
                .andExpect(jsonPath("$.created_at").value("2025-01-02T03:04:00"));
    }

    @Test
    void invalidLocalPasswordReturnsCompatibleError() throws Exception {
        mvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"local@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void registerCreatesPythonCompatibleUserResponse() throws Exception {
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());

        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新用户\",\"email\":\"new@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void duplicateRegistrationReturnsCompatibleError() throws Exception {
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复\",\"email\":\"local@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户已存在"));
    }

    @Test
    void wordpressLoginKeepsExistingPayload() throws Exception {
        when(wordpress.login("u@example.com", "secret"))
                .thenReturn(new WordPressSession("wp-token", 4L, "u@example.com", "WP 用户"));
        when(users.findByEmail("u@example.com")).thenReturn(Optional.empty());

        mvc.perform(post("/api/users/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("wp-token"))
                .andExpect(jsonPath("$.data.info.id").value(4))
                .andExpect(jsonPath("$.data.info.email").value("u@example.com"));
    }
}
