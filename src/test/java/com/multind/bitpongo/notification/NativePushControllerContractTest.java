package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = JacksonAutoConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NativePushControllerContractTest {
    // Load the real application.yml naming policy, not MockMvc's default camel-case mapper.
    @Autowired JsonMapper json;
    NativePushService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(NativePushService.class);
        mvc=MockMvcBuilders.standaloneSetup(new NativePushController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
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
    @Test void settingsReadModifySaveUsesTheInstalledClientsCamelCaseContract() throws Exception {
        when(service.settings(7)).thenReturn(new NativePushController.SettingView(
                false,true,true,false,true,false,true,"zh-CN","Asia/Shanghai",2));
        var response = mvc.perform(get("/api/users/notifications/push")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode preferences = (ObjectNode) json.readTree(response).get("data");
        // The web client removes the two response-only fields before saving.
        preferences.remove("available");
        preferences.remove("deviceCount");
        preferences.put("soundEnabled", false);
        when(service.save(eq(7L),any())).thenReturn(new NativePushController.SettingView(
                false,true,true,false,true,false,false,"zh-CN","Asia/Shanghai",2));
        mvc.perform(put("/api/users/notifications/push").contentType("application/json")
                .content(json.writeValueAsString(preferences)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeSucceeded").value(true))
                .andExpect(jsonPath("$.data.tradeFailed").value(false))
                .andExpect(jsonPath("$.data.planAlerts").value(true))
                .andExpect(jsonPath("$.data.systemAlerts").value(false))
                .andExpect(jsonPath("$.data.soundEnabled").value(false));
        verify(service).save(7L,new NativePushController.Settings(
                true,true,false,true,false,false,"zh-CN","Asia/Shanghai"));
    }
    @Test void deviceListFieldsCanBeMatchedToTheNativeInstallation() throws Exception {
        String id="f1b959fe-52ca-41cb-a75c-c54712f0c58b";
        Instant time=Instant.parse("2026-09-16T00:00:00Z");
        when(service.devices(7)).thenReturn(List.of(new NativePushController.DeviceView(id,"ios","1.0.1",true,time,time)));
        mvc.perform(get("/api/users/notifications/push/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].installationId").value(id))
                .andExpect(jsonPath("$.data[0].appVersion").value("1.0.1"))
                .andExpect(jsonPath("$.data[0].lastSeenAt").value("2026-09-16T00:00:00Z"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-09-16T00:00:00Z"))
                .andExpect(jsonPath("$.data[0].token").doesNotExist());
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
        verify(service).register(7L,id,new NativePushController.Registration(
                "private-device-token","ios","authorized","1","en-us","UTC",false));
    }
    @Test void testAcknowledgementMeansQueuedOnly() throws Exception {
        mvc.perform(post("/api/users/notifications/push/test").contentType("application/json")
                .content("{\"installationId\":\"f1b959fe-52ca-41cb-a75c-c54712f0c58b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.queued").value(true)).andExpect(jsonPath("$.data.sent").doesNotExist());
        verify(service).test(7L,"f1b959fe-52ca-41cb-a75c-c54712f0c58b");
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
