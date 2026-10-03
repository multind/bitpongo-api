package com.multind.bitpongo.market;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AlternativeFearGreedClientTest {
    @Test void parsesProviderHistoryWithoutConvertingMissingValuesToZero() throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"value\":\"67\",\"value_classification\":\"Greed\",\"timestamp\":\"1791072000\"}],\"metadata\":{\"error\":null}}"));
            var client=new AlternativeFearGreedClient(server.url("/").toString(),JsonMapper.builder().build());
            assertThat(client.history().getFirst().value()).isEqualTo(67);
            assertThat(server.takeRequest().getPath()).isEqualTo("/fng/?limit=365&format=json");
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"value_classification\":\"Fear\",\"timestamp\":\"1791072000\"}]}"));
            assertThatThrownBy(client::history).hasMessageContaining("格式");
        }
    }
    @Test void rejectsProviderErrorNonSuccessAndOversizedBodies() throws Exception {
        try(var server=new MockWebServer()) {
            var client=new AlternativeFearGreedClient(server.url("/").toString(),JsonMapper.builder().build());
            server.enqueue(new MockResponse().setBody("{\"data\":[],\"metadata\":{\"error\":\"offline\"}}"));
            assertThatThrownBy(client::history).hasMessageContaining("不可用");
            server.enqueue(new MockResponse().setResponseCode(429));
            assertThatThrownBy(client::history).hasMessageContaining("不可用");
            server.enqueue(new MockResponse().setBody(" ".repeat(100001)));
            assertThatThrownBy(client::history).hasMessageContaining("格式");
        }
    }
}
