package com.multind.bitpongo.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class UserControllerContractTest {

    @Autowired private MockMvc mvc;
    @Autowired private PasswordCompatibilityService passwords;
    @Autowired private JwtTokenService tokens;

    @MockitoBean private UserRepository users;
    @MockitoBean private DeletedExternalIdentityRepository tombstones;
    @MockitoBean private WordPressAuthClient wordpress;
    @MockitoBean private AccountDeletionService accountDeletionService;
    @MockitoBean private com.multind.bitpongo.exchange.ExchangeApplicationService exchangeApplicationService;
    @MockitoBean private com.multind.bitpongo.plan.PlanApplicationService planApplicationService;
    @MockitoBean private com.multind.bitpongo.strategy.StrategyApplicationService strategyApplicationService;

    private UserEntity localUser;

    @BeforeEach
    void setUp() {
        localUser = new UserEntity();
        localUser.setId(7L);
        localUser.setName("测试用户");
        localUser.setEmail("local@example.com");
        localUser.setPassword(passwords.hash("secret"));
        localUser.setStatus("active");
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
        when(users.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
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
    void deletedLocalAccountCannotLoginOrReuseOldToken() throws Exception {
        localUser.setStatus("deleted");

        mvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"local@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/users/profile")
                        .header("Authorization", "Bearer " + tokens.issue(7L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationCreatesSessionAndNormalizesEmail() throws Exception {
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());

        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新用户\",\"email\":\" New@Example.COM \",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.info.id").value(9))
                .andExpect(jsonPath("$.data.info.email").value("new@example.com"));
    }

    @Test
    void registrationRejectsPasswordsThatDoNotMeetTheSecurityRule() throws Exception {
        for (String password : new String[] {"short1", "abcdefgh", "12345678"}) {
            mvc.perform(post("/api/users/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"新用户\",\"email\":\"new@example.com\",\"password\":\""
                                    + password + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("密码至少8位，且必须同时包含字母和数字"));
        }
    }

    @Test
    void duplicateRegistrationReturnsCompatibleError() throws Exception {
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复\",\"email\":\"local@example.com\",\"password\":\"abc12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户已存在"));
    }

    @Test
    void concurrentDuplicateRegistrationReturnsUserAlreadyExists() throws Exception {
        when(users.findByEmail("race@example.com")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate email"));

        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"竞争用户\",\"email\":\"race@example.com\",\"password\":\"abc12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
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

    @Test
    void wordpressLoginCannotRestoreDeletedSubject() throws Exception {
        when(wordpress.login("u@example.com", "secret"))
                .thenReturn(new WordPressSession("wp-token", 4L, "u@example.com", "WP 用户"));
        when(tombstones.existsByProviderAndSubject("wordpress", "4")).thenReturn(true);

        mvc.perform(post("/api/users/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号不可用"));
    }

    @Test
    void authenticatedUserCanDeleteAccountWithPasswordConfirmation() throws Exception {
        mvc.perform(delete("/api/users/account")
                        .header("Authorization", "Bearer " + tokens.issue(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(accountDeletionService).delete(7L, "secret");
    }

    @Test
    void accountDeletionRequiresAuthenticationAndNonBlankPassword() throws Exception {
        mvc.perform(delete("/api/users/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/users/account")
                        .header("Authorization", "Bearer " + tokens.issue(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }
}
