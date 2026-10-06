package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AccountSecurityIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Test void passwordGuessLimitPersistsAcrossFailedRequests() throws Exception {
        jdbc.update("update user set password=? where id=?",passwords.hash("OriginalPass!1"),user);
        String token=tokens.issue(user);
        for(int i=0;i<10;i++)
            mvc.perform(post("/api/users/security/password").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"wrong\",\"newPassword\":\"ChangedPass!2\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/security/password").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"OriginalPass!1\",\"newPassword\":\"ChangedPass!2\"}"))
            .andExpect(status().isTooManyRequests());
        assertThat(passwords.matches("OriginalPass!1",jdbc.queryForObject("select password from user where id=?",String.class,user))).isTrue();
    }

    @Test void changingPasswordRequiresOldPasswordAndInvalidatesUnusedTokens() throws Exception {
        jdbc.update("update user set password=? where id=?",passwords.hash("OriginalPass!1"),user);
        String token=tokens.issue(user), unused=tokens.issue(user);
        mvc.perform(post("/api/users/security/password").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"wrong\",\"newPassword\":\"ChangedPass!2\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+unused)).andExpect(status().isOk());
        String neverUsed=tokens.issue(user);
        mvc.perform(post("/api/users/security/password").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"OriginalPass!1\",\"newPassword\":\"ChangedPass!2\"}"))
            .andExpect(status().isOk());
        for(String old: new String[]{token,unused,neverUsed})
            mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
        assertThat(passwords.matches("ChangedPass!2",jdbc.queryForObject("select password from user where id=?",String.class,user))).isTrue();
        String email=jdbc.queryForObject("select email from user where id=?",String.class,user);
        var login=mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+email+"\",\"password\":\"ChangedPass!2\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty()).andReturn().getResponse().getContentAsString();
        String fresh=new com.fasterxml.jackson.databind.ObjectMapper().readTree(login).path("data").path("token").asText();
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+fresh)).andExpect(status().isOk());
    }
}
