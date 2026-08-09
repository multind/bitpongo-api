package com.multind.zhitoubao.market;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class PriceWebSocketConfiguration implements WebSocketConfigurer {
    private final PriceWebSocketHandler handler;
    private final String[] allowedOrigins;

    public PriceWebSocketConfiguration(
            PriceWebSocketHandler handler,
            @Value("${zhitoubao.cors.allowed-origins:*}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/price")
                .setAllowedOriginPatterns(allowedOrigins.length == 0 ? new String[]{"*"} : allowedOrigins);
    }
}
