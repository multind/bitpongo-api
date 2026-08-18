package com.multind.bitpongo.auth;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpWordPressAuthClientTest {

    private MockWebServer server;
    private HttpWordPressAuthClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new HttpWordPressAuthClient(
                server.url("/").toString(), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void loginMapsWordPressTokenAndUser() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"token\":\"wp-token\",\"user_email\":\"u@example.com\","
                        + "\"user_display_name\":\"WP 用户\",\"data\":{\"user\":{\"id\":4}}}")
                );

        WordPressSession session = client.login("u@example.com", "secret");

        assertThat(session).isEqualTo(new WordPressSession("wp-token", 4L, "u@example.com", "WP 用户"));
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/wp-json/jwt-auth/v1/token");
        assertThat(request.getBody().readUtf8()).contains("\"username\":\"u@example.com\"")
                .contains("\"password\":\"secret\"");
    }

    @Test
    void resolveUserUsesBearerToken() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\":4,\"name\":\"WP 用户\",\"email\":\"u@example.com\"}"));

        assertThat(client.resolveUser("wp-token").id()).isEqualTo(4L);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/wp-json/wp/v2/users/me");
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer wp-token");
    }

    @Test
    void unauthorizedLoginHasCompatibleMessage() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));

        assertThatThrownBy(() -> client.login("u@example.com", "wrong"))
                .hasMessage("登录失败，请检查用户名和密码");
    }
}
