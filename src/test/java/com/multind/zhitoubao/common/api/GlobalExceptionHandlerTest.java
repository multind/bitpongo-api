package com.multind.zhitoubao.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.FailingController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void businessExceptionKeepsCompatibleEnvelope() throws Exception {
        mvc.perform(get("/test/business-error"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("交易计划不存在"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @RestController
    public static class FailingController {

        @GetMapping("/test/business-error")
        void fail() {
            throw new BusinessException(404, "交易计划不存在");
        }
    }
}
