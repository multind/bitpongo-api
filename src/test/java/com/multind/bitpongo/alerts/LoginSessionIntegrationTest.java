package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class LoginSessionIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired com.multind.bitpongo.auth.LoginSessionService sessions;

    @Test void revocationOnlyInvalidatesSelectedLogin() throws Exception {
        String current=tokens.issue(user), other=tokens.issue(user);
        assertThat(current).isNotEqualTo(other);
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+other)).andExpect(status().isOk());
        mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+current))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[?(@.current == true)]").isNotEmpty())
            .andExpect(jsonPath("$.data.items[0].tokenHash").doesNotExist());
        String target=jdbc.queryForObject("select id from login_session where user_id=? and token_hash=?",String.class,user,hash(other));
        mvc.perform(delete("/api/users/sessions/"+target).header("Authorization","Bearer "+current)).andExpect(status().isOk());
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+current)).andExpect(status().isOk());
    }

    @Test void currentLoginCanBeRevokedWithoutExposingItsToken() throws Exception {
        String token=tokens.issue(user);
        mvc.perform(get("/api/users/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        String id=jdbc.queryForObject("select id from login_session where token_hash=?",String.class,hash(token));
        mvc.perform(delete("/api/users/sessions/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select token_hash from login_session where id=?",String.class,id)).doesNotContain(token);
    }

    private static String hash(String token) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void preMigrationTokenRemainsValidAndRevocationCannotBeRecreated() throws Exception {
        var encoder=java.util.Base64.getUrlEncoder().withoutPadding();
        String header=encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload=encoder.encodeToString(("{\"id\":"+user+",\"exp\":"+java.time.Instant.now().plusSeconds(3600).getEpochSecond()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("price-alert-test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        String content=header+"."+payload, token=content+"."+encoder.encodeToString(mac.doFinal(content.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        String id=jdbc.queryForObject("select id from login_session where token_hash=?",String.class,hash(token));
        mvc.perform(delete("/api/users/sessions/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        for(int i=0;i<2;i++) mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select revoked_at is not null from login_session where id=?",Boolean.class,id)).isTrue();
    }

    @Test void anotherAccountCannotRevokeOrListCurrentAccountsSession() throws Exception {
        String token=tokens.issue(user);
        mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        String id=jdbc.queryForObject("select id from login_session where token_hash=?",String.class,hash(token));
        jdbc.update("insert into user(name,email,password) values('other',?,'unused')",java.util.UUID.randomUUID()+"@example.com");
        long other=jdbc.queryForObject("select last_insert_id()",Long.class);
        String otherToken=tokens.issue(other);
        mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+otherToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete("/api/users/sessions/"+id).header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }

    @Test void cleanupRemovesExpiredRecordsButRetainsUnexpiredRevocations() throws Exception {
        String token=tokens.issue(user);
        mvc.perform(get("/api/users/sessions").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        String id=jdbc.queryForObject("select id from login_session where token_hash=?",String.class,hash(token));
        sessions.revoke(user,id);
        jdbc.update("insert into login_session(id,user_id,token_hash,created_at,last_seen_at,expires_at,revoked_at) values(?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),?,UTC_TIMESTAMP())",
                java.util.UUID.randomUUID().toString(),user,hash("expired-test-record"),java.time.LocalDateTime.ofInstant(java.time.Instant.now().minusSeconds(172800),java.time.ZoneOffset.UTC));
        sessions.cleanupExpired();
        assertThat(jdbc.queryForObject("select count(*) from login_session where user_id=?",Integer.class,user)).isEqualTo(1);
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
}
