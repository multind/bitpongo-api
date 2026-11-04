package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AccountRecoveryIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired com.multind.bitpongo.auth.AccountRecoveryService recovery;
    @MockitoBean com.multind.bitpongo.auth.EmailCodeSender mail;
    String sentCode;
    String challenge(String purpose,String email,String token) throws Exception {
        when(mail.available()).thenReturn(true);
        doAnswer(call->{sentCode=call.getArgument(1); return null;}).when(mail).send(anyString(),anyString(),anyString());
        var request=post("/api/users/security/code").contentType(MediaType.APPLICATION_JSON)
            .content("{\"purpose\":\""+purpose+"\",\"email\":\""+email+"\",\"oldPassword\":\"OriginalPass!1\"}");
        if(token!=null) request.header("Authorization","Bearer "+token);
        var response=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("challengeId").asText();
    }
    void prepare() { jdbc.update("update user set password=? where id=?",passwords.hash("OriginalPass!1"),user); }
    String email() { return jdbc.queryForObject("select email from user where id=?",String.class,user); }
    @Test void reminderEmailVerificationPreservesLoginAndSupportsReturningToDefault() throws Exception {
        String original=email(), token=tokens.issue(user), authorization="Bearer "+token;
        String target=java.util.UUID.randomUUID()+"@example.com";
        String path="/api/users/security/reminder-email";
        String id=challenge("REMINDER_EMAIL",target,token);
        mvc.perform(get(path).header("Authorization",authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountEmail").value(original));
        mvc.perform(post(path).header("Authorization",authorization).contentType(MediaType.APPLICATION_JSON)
            .content("{\"challengeId\":\""+id+"\",\"code\":\""+sentCode+"\"}")).andExpect(status().isOk());
        assertThat(email()).isEqualTo(original);
        mvc.perform(get(path).header("Authorization",authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.customEmail").value(target));
        mvc.perform(delete(path).header("Authorization",authorization)).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization",authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.customEmail").doesNotExist());
    }
    void reset(String id,String code,int status) throws Exception {
        mvc.perform(post("/api/users/security/reset").contentType(MediaType.APPLICATION_JSON)
            .content("{\"challengeId\":\""+id+"\",\"code\":\""+code+"\",\"newPassword\":\"RecoveredPass!2\"}"))
            .andExpect(status().is(status));
    }
    @Test void resetUsesSingleUseCodeAndRevokesEvenUnusedTokens() throws Exception {
        prepare(); String unused=tokens.issue(user), id=challenge("RESET",email(),null), code=sentCode;
        assertThat(code).matches("[0-9]{8}");
        assertThat(jdbc.queryForObject("select code_hash from account_email_challenge where id=?",String.class,id)).doesNotContain(code);
        reset(id,code,200);
        reset(id,code,400);
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+unused)).andExpect(status().isUnauthorized());
        assertThat(passwords.matches("RecoveredPass!2",jdbc.queryForObject("select password from user where id=?",String.class,user))).isTrue();
    }
    @Test void failedAttemptsPersistAndLockOutCorrectCodeAfterFiveTries() throws Exception {
        prepare(); String id=challenge("RESET",email(),null), code=sentCode;
        String wrong=code.equals("00000000")?"11111111":"00000000";
        for(int i=0;i<5;i++) reset(id,wrong,400);
        assertThat(jdbc.queryForObject("select attempts from account_email_challenge where id=?",Integer.class,id)).isEqualTo(5);
        reset(id,code,400);
        assertThat(passwords.matches("OriginalPass!1",jdbc.queryForObject("select password from user where id=?",String.class,user))).isTrue();
    }
    @Test void expiredCodeAndUnknownAccountCannotResetPassword() throws Exception {
        prepare(); String id=challenge("RESET",email(),null), code=sentCode;
        jdbc.update("update account_email_challenge set expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND where id=?",id);
        reset(id,code,400);
        String unknown=challenge("RESET",java.util.UUID.randomUUID()+"@example.com",null);
        reset(unknown,"00000000",400);
    }
    @Test void changingEmailChecksOwnerPurposeAndOldPasswordAndSignsOut() throws Exception {
        prepare(); String token=tokens.issue(user), target=java.util.UUID.randomUUID()+"@example.com", id=challenge("EMAIL",target,token), code=sentCode;
        reset(id,code,400);
        String body="{\"challengeId\":\""+id+"\",\"code\":\""+code+"\",\"oldPassword\":\"OriginalPass!1\"}";
        jdbc.update("insert into user(name,email,password) values('other',?,'unused')",java.util.UUID.randomUUID()+"@example.com");
        long other=jdbc.queryForObject("select last_insert_id()",Long.class);
        mvc.perform(post("/api/users/security/email").header("Authorization","Bearer "+tokens.issue(other)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/security/email").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body.replace("OriginalPass!1","wrong"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/security/email").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(email()).isEqualTo(target);
        mvc.perform(get("/api/users/profile").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void emailRequestHasCooldownAndUnavailableIsExplicit() throws Exception {
        prepare(); challenge("RESET",email(),null);
        mvc.perform(post("/api/users/security/code").contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"RESET\",\"email\":\""+email()+"\"}")).andExpect(status().isTooManyRequests());
        when(mail.available()).thenReturn(false);
        mvc.perform(get("/api/users/security/status")).andExpect(status().isOk()).andExpect(jsonPath("$.data.emailEnabled").value(false));
        mvc.perform(post("/api/users/security/code").contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"RESET\",\"email\":\"x@example.com\"}")).andExpect(status().isServiceUnavailable());
    }
    @Test void resendingInvalidatesEarlierCodeAndConcurrentRedemptionSucceedsOnlyOnce() throws Exception {
        prepare(); String first=challenge("RESET",email(),null), previous=sentCode;
        jdbc.update("update account_security_rate set last_sent_at=UTC_TIMESTAMP()-INTERVAL 61 SECOND");
        String second=challenge("RESET",email(),null), code=sentCode;
        reset(first,previous,400);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{try {recovery.reset(second,code,"RecoveredPass!2");return true;}catch(com.multind.bitpongo.common.api.BusinessException e){return false;}});
            var b=pool.submit(()->{try {recovery.reset(second,code,"RecoveredPass!2");return true;}catch(com.multind.bitpongo.common.api.BusinessException e){return false;}});
            assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select security_version from user where id=?",Long.class,user)).isEqualTo(1L);
    }
    @Test void smtpFailureDoesNotExposeProviderDetailsOrLeaveRedeemableCode() throws Exception {
        prepare(); when(mail.available()).thenReturn(true);
        doThrow(new IllegalStateException("provider-private-details")).when(mail).send(anyString(),anyString(),anyString());
        var response=mvc.perform(post("/api/users/security/code").contentType(MediaType.APPLICATION_JSON)
            .content("{\"purpose\":\"RESET\",\"email\":\""+email()+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("provider-private-details");
        String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("challengeId").asText();
        assertThat(jdbc.queryForObject("select consumed from account_email_challenge where id=?",Boolean.class,id)).isTrue();
    }
    @Test void deletingAccountRemovesPendingRecoveryChallenge() throws Exception {
        prepare(); challenge("RESET",email(),null);
        accounts.delete(user,"OriginalPass!1");
        assertThat(jdbc.queryForObject("select count(*) from account_email_challenge where user_id=?",Integer.class,user)).isZero();
    }
}
