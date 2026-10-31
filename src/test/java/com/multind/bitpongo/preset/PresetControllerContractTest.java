package com.multind.bitpongo.preset;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
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
class PresetControllerContractTest {
    @Autowired JsonMapper json;
    PresetService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(PresetService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PresetController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == AuthenticatedUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory f) {
                        return new AuthenticatedUser(7, "test@example.com", "Test");
                    }
                }).build();
    }

    private static PresetDtos.Preset preset() {
        return new PresetDtos.Preset(
                "dca:1", "DCA", "BTC DCA", "Test", "data:image/png;base64,YQ==", false, true, 2, false,
                "1801.9", "180.19", "binance", List.of("BTC"), 128L,
                List.of("10", "50", "180.19"), Map.of("frequency", "daily", "cron", "0 0 8 * * ?"));
    }

    @Test void listsPresetsWithCamelCaseFields() throws Exception {
        when(service.list(7, "BTC", "return", "all", "binance", "all", "all", 0, 20))
                .thenReturn(new PresetDtos.Page(List.of(preset()), 0, 20, 1, false));
        mvc.perform(get("/api/presets").param("q", "BTC").param("sort", "return")
                        .param("exchange", "binance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].key").value("dca:1"))
                .andExpect(jsonPath("$.data.items[0].authorAvatar").value("data:image/png;base64,YQ=="))
                .andExpect(jsonPath("$.data.items[0].totalRatio").value("180.19"))
                .andExpect(jsonPath("$.data.items[0].recommended").value(true))
                .andExpect(jsonPath("$.data.items[0].runtimeDays").value(128))
                .andExpect(jsonPath("$.data.items[0].sparkline[0]").value("10"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test void returnsPresetDetailAndTogglesFavorite() throws Exception {
        when(service.detail(7, "dca:1")).thenReturn(preset());
        when(service.favorite(7, "dca:1")).thenReturn(new PresetDtos.Favorite(true, 3));
        mvc.perform(get("/api/presets/dca:1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("BTC DCA"));
        mvc.perform(post("/api/presets/dca:1/favorite")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true))
                .andExpect(jsonPath("$.data.followers").value(3));
        verify(service).favorite(7, "dca:1");
    }
}
