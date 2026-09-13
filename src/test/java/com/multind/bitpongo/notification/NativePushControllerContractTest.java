package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativePushControllerContractTest {
    NativePushService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(NativePushService.class);
        mvc=MockMvcBuilders.standaloneSetup(new NativePushController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) {return p.getParameterType()==AuthenticatedUser.class;}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest r,WebDataBinderFactory f) {
                        return new AuthenticatedUser(7,"test@example.com","Test");
                    }
                }).build();
    }
    @Test void settingsEnvelopeDoesNotExposeTokensOrProviderCredentials() throws Exception {
        when(service.settings(7)).thenReturn(new NativePushController.SettingView(false,true,true,true,true,true,true,"zh-CN","Asia/Shanghai",2));
        mvc.perform(get("/api/users/notifications/push")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false)).andExpect(jsonPath("$.data.deviceCount").value(2))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }
    @Test void injectedUserIdIsRejectedRatherThanChangingTheRecipient() throws Exception {
        mvc.perform(put("/api/users/notifications/push").contentType("application/json").content("""
                {"enabled":true,"tradeSucceeded":true,"tradeFailed":true,"planAlerts":true,"systemAlerts":true,
                 "soundEnabled":true,"locale":"en-US","timezone":"UTC","userId":99}
                """)).andExpect(status().isBadRequest());
        verify(service,never()).save(anyLong(),any());
    }
    @Test void deviceRegistrationUsesPrincipalAndReturnsRevocationResult() throws Exception {
        String id="f1b959fe-52ca-41cb-a75c-c54712f0c58b";
        when(service.register(eq(7L),eq(id),any())).thenReturn(false);
        mvc.perform(put("/api/users/notifications/push/devices/"+id).contentType("application/json").content("""
                {"token":"private-device-token","platform":"ios","permission":"authorized","appVersion":"1",
                 "locale":"en-us","timezone":"UTC","activate":false}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.registered").value(false));
        verify(service).register(eq(7L),eq(id),any());
    }
    @Test void testAcknowledgementMeansQueuedOnly() throws Exception {
        mvc.perform(post("/api/users/notifications/push/test").contentType("application/json")
                .content("{\"installationId\":\"f1b959fe-52ca-41cb-a75c-c54712f0c58b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.queued").value(true)).andExpect(jsonPath("$.data.sent").doesNotExist());
    }
    @Test void logoutEndpointUsesPrincipalAndRejectsUnexpectedOwnershipField() throws Exception {
        String id="f1b959fe-52ca-41cb-a75c-c54712f0c58b";
        mvc.perform(post("/api/users/notifications/push/devices/"+id+"/logout").contentType("application/json")
                .content("{\"token\":\"old-token\"}"))
                .andExpect(status().isOk());
        verify(service).logout(7L,id,"old-token");
        mvc.perform(post("/api/users/notifications/push/devices/"+id+"/logout").contentType("application/json")
                .content("{\"token\":\"old-token\",\"userId\":99}"))
                .andExpect(status().isBadRequest());
    }
}
