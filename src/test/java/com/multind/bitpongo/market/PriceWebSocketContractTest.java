package com.multind.bitpongo.market;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class PriceWebSocketContractTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final PriceCache cache = new PriceCache(Duration.ofSeconds(60));
    private final PriceWebSocketHandler handler = new PriceWebSocketHandler(
            cache, new SymbolNormalizer(), JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void captureMessages() throws Exception {
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void subscriptionReturnsOneMessagePerAvailableSymbol() throws Exception {
        cache.put("binance", "BTC/USDT", new BigDecimal("62000"), now);
        cache.put("binance", "ETH/USDT", new BigDecimal("3200"), now);

        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribe\",\"symbols\":[\"BTC\",\"ETH\"],\"exchange\":\"binance\"}"));

        assertThat(sent).containsExactly(
                "{\"symbol\":\"BTC\",\"price\":62000,\"exchange\":\"binance\"}",
                "{\"symbol\":\"ETH\",\"price\":3200,\"exchange\":\"binance\"}");
    }

    @Test
    void missingPriceIsSkippedAndDefaultExchangeIsBinance() throws Exception {
        cache.put("binance", "BTC/USDT", new BigDecimal("62000"), now);
        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribe\",\"symbols\":[\"BTC\",\"DOGE\"]}"));
        assertThat(sent).hasSize(1).first().asString().contains("\"symbol\":\"BTC\"");
    }

    @Test
    void invalidJsonAndUnsupportedExchangeReturnIsolatedErrors() throws Exception {
        handler.handleTextMessage(session, new TextMessage("not-json"));
        handler.handleTextMessage(session, new TextMessage(
                "{\"action\":\"subscribe\",\"symbols\":[\"BTC\"],\"exchange\":\"okx\"}"));
        assertThat(sent).containsExactly(
                "{\"error\":\"Invalid JSON\"}",
                "{\"error\":\"Unsupported exchange: okx\"}");
    }
}
