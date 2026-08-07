package com.multind.zhitoubao.exchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ExchangeDtos {
    private ExchangeDtos() {}

    public record ExchangeUpsertRequest(
            String name,
            String exchange,
            @JsonProperty("access_key") String accessKey,
            @JsonProperty("secret_key") String secretKey,
            String password,
            String status,
            @JsonProperty("user_id") Long ignoredUserId) {}

    public record ExchangeCheckRequest(
            Long id,
            String exchange,
            @JsonProperty("access_key") String accessKey,
            @JsonProperty("secret_key") String secretKey,
            String password) {}

    public record MinimumAmountRequest(
            @JsonProperty("exchange_id") long exchangeId,
            List<String> coins) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangeView(
            long id,
            String name,
            String exchange,
            @JsonProperty("access_key") String accessKey,
            @JsonProperty("secret_key") String secretKey,
            String password,
            String status,
            @JsonProperty("user_id") long userId,
            @JsonProperty("created_at") LocalDateTime createdAt) {}

    public record BalanceView(String asset, BigDecimal free, BigDecimal locked) {}
}
