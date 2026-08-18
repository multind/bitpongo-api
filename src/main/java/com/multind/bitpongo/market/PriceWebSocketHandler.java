package com.multind.bitpongo.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class PriceWebSocketHandler extends TextWebSocketHandler {
    private final PriceCache prices;
    private final SymbolNormalizer symbols;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public PriceWebSocketHandler(PriceCache prices, SymbolNormalizer symbols, ObjectMapper json) {
        this(prices, symbols, json, Clock.systemUTC());
    }

    PriceWebSocketHandler(PriceCache prices, SymbolNormalizer symbols, ObjectMapper json, Clock clock) {
        this.prices = prices;
        this.symbols = symbols;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        try {
            PriceSubscription request = json.readValue(message.getPayload(), PriceSubscription.class);
            handleSubscription(session, request);
        } catch (tools.jackson.core.JacksonException exception) {
            send(session, new ErrorMessage("Invalid JSON"));
        } catch (IllegalArgumentException exception) {
            send(session, new ErrorMessage(exception.getMessage()));
        } catch (Exception exception) {
            send(session, new ErrorMessage("Request failed"));
        }
    }

    private void handleSubscription(WebSocketSession session, PriceSubscription request) throws IOException {
        if (request == null || !"subscribe".equals(request.action())) {
            throw new IllegalArgumentException("Unsupported action");
        }
        String exchange = request.exchange() == null || request.exchange().isBlank()
                ? "binance" : request.exchange().trim().toLowerCase(Locale.ROOT);
        if (!"binance".equals(exchange)) {
            throw new IllegalArgumentException("Unsupported exchange: " + exchange);
        }
        List<String> requestedSymbols = request.symbols() == null ? List.of() : request.symbols();
        for (String requested : requestedSymbols) {
            String binance = symbols.toBinance(requested);
            String internal = symbols.toInternal(binance);
            prices.getFresh(exchange, internal, clock.instant())
                    .ifPresent(price -> sendUnchecked(
                            session, new PriceMessage(baseSymbol(internal), price, exchange)));
        }
    }

    private void sendUnchecked(WebSocketSession session, Object value) {
        try {
            send(session, value);
        } catch (IOException exception) {
            throw new SessionWriteException(exception);
        }
    }

    private void send(WebSocketSession session, Object value) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(value)));
    }

    private static String baseSymbol(String internal) {
        return internal.substring(0, internal.indexOf('/'));
    }

    private record PriceMessage(String symbol, BigDecimal price, String exchange) {}
    private record ErrorMessage(String error) {}

    private static final class SessionWriteException extends RuntimeException {
        SessionWriteException(IOException cause) { super(cause); }
    }
}
