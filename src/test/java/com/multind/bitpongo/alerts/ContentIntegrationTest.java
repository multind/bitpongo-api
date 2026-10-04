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
class ContentIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    private String bearer() { return "Bearer "+tokens.issue(user); }
    @Test void deletionErasesSearchHistoryAndHistoryRetentionIsBounded() throws Exception {
        String auth=bearer();
        for(int i=0;i<22;i++)mvc.perform(post("/api/content/search-history").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"query-"+i+"\"}")).andExpect(status().isOk());
        assertThat(count("help_search_history")).isEqualTo(20);
        jdbc.update("update user set password=? where id=?",passwords.hash("Delete-me-123"),user);
        accounts.delete(user,"Delete-me-123");
        assertThat(count("help_search_history")).isZero();
    }
    @Test void helpSearchUsesSelectedLanguageAndPublishedArticlesOnly() throws Exception {
        jdbc.update("insert into content_article values('aaa-hidden','en-us','HELP','notification hidden','','secret',false,'2026-10-04 00:00:00')");
        jdbc.update("insert into content_article values('future','en-us','HELP','notification future','','secret',true,'2040-01-01 00:00:00')");
        mvc.perform(get("/api/content/articles").param("kind","HELP").param("locale","en-US").param("q","notification").header("Authorization",bearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].slug").value("notifications"))
            .andExpect(jsonPath("$.data.items[0].publishedAt").exists()).andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/api/content/articles/notifications").param("locale","zh-TW").header("Authorization",bearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("接收通知"));
        mvc.perform(get("/api/content/articles/missing").header("Authorization",bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/content/articles").param("q","%").header("Authorization",bearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/content/articles").param("page","-1").header("Authorization",bearer())).andExpect(status().isBadRequest());
    }
    @Test void articlePaginationDoesNotRepeatThePreviousPage() throws Exception {
        for(int i=0;i<21;i++)jdbc.update("insert into content_article values(?,'en-us','ARTICLE',?,'summary','plain text',true,'2026-10-04 00:00:00')","pagination-"+String.format("%02d",i),"Pagination "+i);
        mvc.perform(get("/api/content/articles").param("kind","ARTICLE").param("q","Pagination").header("Authorization",bearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(20)).andExpect(jsonPath("$.data.hasMore").value(true))
            .andExpect(jsonPath("$.data.items[0].slug").value("pagination-00"));
        mvc.perform(get("/api/content/articles").param("kind","ARTICLE").param("q","Pagination").param("page","1").header("Authorization",bearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.hasMore").value(false))
            .andExpect(jsonPath("$.data.items[0].slug").value("pagination-20"));
    }
    @Test void searchHistoryIsOwnedDeduplicatedAndCanBeCleared() throws Exception {
        String auth=bearer();
        for(int i=0;i<2;i++) mvc.perform(post("/api/content/search-history").header("Authorization",auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"  notifications  \"}" )).andExpect(status().isOk());
        mvc.perform(get("/api/content/search-history").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].query").value("notifications"));
        jdbc.update("insert into user(name,email,password) values('other',?,'unused')",java.util.UUID.randomUUID()+"@example.com");
        long stranger=jdbc.queryForObject("select last_insert_id()",Long.class);
        Long id=jdbc.queryForObject("select id from help_search_history where user_id=?",Long.class,user);
        mvc.perform(delete("/api/content/search-history/"+id).header("Authorization","Bearer "+tokens.issue(stranger))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/content/search-history").header("Authorization",auth)).andExpect(status().isOk());
        assertThat(count("help_search_history")).isZero();
        mvc.perform(post("/api/content/search-history").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\" \"}" )).andExpect(status().isBadRequest());
    }
}
