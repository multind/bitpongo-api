package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = JacksonAutoConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationChannelControllerContractTest {
    @Autowired JsonMapper json;
    NotificationChannelService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(NotificationChannelService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationChannelController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == AuthenticatedUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory f) {
                        return new AuthenticatedUser(7, "test@example.com", "Test");
                    }
                }).build();
    }

    private static NotificationChannelService.Channel channel(boolean enabled, boolean available, String level, int sequence) {
        return new NotificationChannelService.Channel(enabled, available, level, sequence);
    }

    @Test void exposesChannelAvailabilityAndPreferencesInCamelCase() throws Exception {
        when(service.get(7)).thenReturn(new NotificationChannelService.Channels(
                channel(true, true, "ModerateWeak", 1),
                channel(false, false, "Weak", 1),
                channel(false, false, "ModerateStrong", 1),
                channel(false, false, "Strong", 1),
                channel(false, false, "Moderate", 1),
                channel(false, false, "Moderate", 2),
                channel(false, false, "Moderate", 3),
                false));
        mvc.perform(get("/api/users/notifications/channels")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bitpongo.enabled").value(true))
                .andExpect(jsonPath("$.data.bitpongo.available").value(true))
                .andExpect(jsonPath("$.data.email.available").value(false))
                .andExpect(jsonPath("$.data.call.level").value("Strong"))
                .andExpect(jsonPath("$.data.confirmationEnabled").value(false));
    }

    @Test void savesCamelCasePreferences() throws Exception {
        when(service.update(eq(7L), any())).thenReturn(new NotificationChannelService.Channels(
                channel(true, true, "ModerateWeak", 1),
                channel(true, false, "Weak", 1),
                channel(false, false, "ModerateStrong", 1),
                channel(false, false, "Strong", 1),
                channel(false, false, "Moderate", 1),
                channel(false, false, "Moderate", 2),
                channel(false, false, "Moderate", 3),
                true));
        mvc.perform(put("/api/users/notifications/channels").contentType("application/json")
                        .content("""
                                {"emailEnabled":true,"messageEnabled":false,"callEnabled":false,
                                 "dingdingEnabled":false,"feishuEnabled":false,"telegramEnabled":false,
                                 "confirmationEnabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email.enabled").value(true))
                .andExpect(jsonPath("$.data.confirmationEnabled").value(true));
        verify(service).update(7L, new NotificationChannelService.Update(
                true, false, false, false, false, false, true));
    }

    @Test void rejectsPartialPreferences() throws Exception {
        mvc.perform(put("/api/users/notifications/channels").contentType("application/json")
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void rejectsUnknownFields() throws Exception {
        mvc.perform(put("/api/users/notifications/channels").contentType("application/json")
                        .content("{\"emailEnabled\":true,\"smsProviderKey\":\"secret\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}