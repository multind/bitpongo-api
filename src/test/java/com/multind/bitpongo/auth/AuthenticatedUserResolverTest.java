package com.multind.bitpongo.auth;

import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.plan.PlanApplicationService;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
@MockitoBean(types = {
        com.multind.bitpongo.plan.AssetSnapshotService.class,
        com.multind.bitpongo.scheduler.OrderPersistenceService.class,
        com.multind.bitpongo.scheduler.OrderReconciliationService.class,
        com.multind.bitpongo.scheduler.ScheduledPurchaseService.class
})
class AuthenticatedUserResolverTest {

    @Autowired private AuthenticatedUserResolver resolver;
    @Autowired private JwtTokenService tokens;

    @MockitoBean private UserRepository users;
    @MockitoBean private DeletedExternalIdentityRepository tombstones;
    @MockitoBean private WordPressAuthClient wordpress;
    @MockitoBean private AccountDeletionService accountDeletionService;
    @MockitoBean private ExchangeRepository exchanges;
    @MockitoBean private PlanApplicationService planApplicationService;
    @MockitoBean private StrategyApplicationService strategyApplicationService;

    @Test
    void localJwtForDeletedUserIsRejected() {
        UserEntity deleted = new UserEntity();
        deleted.setId(7L);
        deleted.setStatus("deleted");
        when(users.findById(7L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> resolver.resolve(tokens.issue(7L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号不可用");
    }

    @Test
    void wordpressTokenForDeletedSubjectIsRejected() {
        when(wordpress.resolveUser("wp-token"))
                .thenReturn(new AuthenticatedUser(7L, "old@example.com", "Old"));
        when(tombstones.existsByProviderAndSubject("wordpress", "7")).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve("wp-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号不可用");
    }
}
