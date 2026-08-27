package com.multind.bitpongo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import com.multind.bitpongo.auth.UserDtos.UserResponse;
import com.multind.bitpongo.exchange.ExchangeDtos.ExchangeView;
import com.multind.bitpongo.plan.OrderEntity;
import com.multind.bitpongo.plan.PlanDtos.PlanView;
import com.multind.bitpongo.plan.SnapshotEntity;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.StrategyEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTimestampShapeTest {

    private static final Pattern UTC_INSTANT = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$");

    private final ObjectMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void serializesEveryAbsoluteApiTimestampAsAnRfc3339UtcInstant() {
        LocalDateTime stored = LocalDateTime.parse("2026-08-25T13:00:00");
        Instant instant = Instant.parse("2026-08-25T13:00:00Z");
        StrategyEntity strategy = new StrategyEntity();
        strategy.setCreatedAt(stored);
        CoinEntity coin = new CoinEntity();
        coin.setCreatedAt(stored);
        OrderEntity order = new OrderEntity();
        order.setCreatedAt(stored);
        SnapshotEntity snapshot = new SnapshotEntity();
        snapshot.setCreatedAt(stored);
        PlanView plan = new PlanView(
                1L, null, null, null, null, instant, instant, "active", 2L, 1,
                instant, strategy, List.of(coin), List.of(order), List.of(snapshot));
        ExchangeView exchange = new ExchangeView(
                3L, "main", "binance", null, null, null, "active", 2L, instant);
        UserResponse user = new UserResponse(2L, "user", "user@example.com", instant);

        assertTimestampShape(json.valueToTree(Map.of(
                "plan", plan,
                "exchange", exchange,
                "user", user)));
    }

    private static void assertTimestampShape(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (!entry.getValue().isNull()
                        && (entry.getKey().endsWith("_at") || entry.getKey().endsWith("_time"))) {
                    assertThat(entry.getValue().isTextual())
                            .as("%s must be textual", entry.getKey()).isTrue();
                    assertThat(entry.getValue().textValue())
                            .as("%s must be RFC-3339 UTC", entry.getKey())
                            .matches(UTC_INSTANT);
                }
                assertTimestampShape(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(ApiTimestampShapeTest::assertTimestampShape);
        }
    }
}
