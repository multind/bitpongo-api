package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.multind.bitpongo.auth.JwtTokenService;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class UserProfileIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired UserProfileService profiles;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;

    @Test void existingAccountHasDefaultsAndChangesPersistWithoutChangingCredentials() {
        assertThat(profiles.get(user).name()).isEqualTo("alerts");
        assertThat(profiles.get(user).upDownColor()).isEqualTo("GREEN_UP");
        String email=jdbc.queryForObject("select email from user where id=?",String.class,user);
        profiles.save(user,new UserProfileService.Profile("  Alice  ","hk","  Personal introduction  ","RED_UP"));
        assertThat(profiles.get(user)).isEqualTo(new UserProfileService.Profile("Alice","HK","Personal introduction","RED_UP"));
        assertThat(jdbc.queryForObject("select name from user where id=?",String.class,user)).isEqualTo("Alice");
        assertThat(jdbc.queryForObject("select email from user where id=?",String.class,user)).isEqualTo(email);
        assertThat(jdbc.queryForObject("select password from user where id=?",String.class,user)).isEqualTo("unused");
    }
    @Test void legacyAccountWithNullNicknameGetsAnEditableEmptyName() {
        jdbc.update("update user set name=null where id=?",user);
        assertThat(profiles.get(user).name()).isEmpty();
    }

    @Test void invalidFieldsAndUnavailableAccountsCannotChangeProfile() {
        assertThatThrownBy(()->profiles.save(user,new UserProfileService.Profile(" ","","","GREEN_UP"))).hasMessageContaining("资料");
        assertThatThrownBy(()->profiles.save(user,new UserProfileService.Profile("Alice","XX","","GREEN_UP"))).hasMessageContaining("资料");
        assertThatThrownBy(()->profiles.save(user,new UserProfileService.Profile("Alice","","x".repeat(501),"GREEN_UP"))).hasMessageContaining("资料");
        assertThatThrownBy(()->profiles.save(user,new UserProfileService.Profile("Alice","","","other"))).hasMessageContaining("资料");
        assertThatThrownBy(()->profiles.save(user+999999,new UserProfileService.Profile("Alice","","","RED_UP"))).hasMessageContaining("账号");
        assertThat(profiles.get(user).name()).isEqualTo("alerts");
        jdbc.update("update user set status='deleted' where id=?",user);
        assertThatThrownBy(()->profiles.get(user)).hasMessageContaining("账号");
    }
    @Test void accountDeletionRemovesPersonalProfileDetails() {
        profiles.save(user,new UserProfileService.Profile("Alice","HK","Personal biography","RED_UP"));
        jdbc.update("update user set password=? where id=?",passwords.hash("Delete-me-123"),user);
        accounts.delete(user,"Delete-me-123");
        assertThat(count("user_profile_preferences")).isZero();
    }
    @Test void profileEndpointRequiresAuthenticationAndDoesNotAcceptAccountFields() throws Exception {
        mvc.perform(get("/api/users/profile/details")).andExpect(status().isUnauthorized());
        String email=jdbc.queryForObject("select email from user where id=?",String.class,user);
        mvc.perform(put("/api/users/profile/details").header("Authorization","Bearer "+tokens.issue(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Alice","country":"HK","introduction":"Hello","upDownColor":"RED_UP",
                     "user_id":999999,"email":"attacker@example.com","password":"modified","status":"deleted"}
                    """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Alice"));
        assertThat(jdbc.queryForObject("select email from user where id=?",String.class,user)).isEqualTo(email);
        assertThat(jdbc.queryForObject("select password from user where id=?",String.class,user)).isEqualTo("unused");
        assertThat(jdbc.queryForObject("select status from user where id=?",String.class,user)).isEqualTo("active");
    }
}
