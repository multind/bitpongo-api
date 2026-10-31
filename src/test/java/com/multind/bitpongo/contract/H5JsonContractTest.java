package com.multind.bitpongo.contract;

import com.multind.bitpongo.alerts.AlertDtos;
import com.multind.bitpongo.alerts.StrategySubscriptionService;
import com.multind.bitpongo.alerts.WebhookService;
import com.multind.bitpongo.auth.UserDtos;
import com.multind.bitpongo.auth.UserProfileService;
import com.multind.bitpongo.market.FearGreedService;
import com.multind.bitpongo.market.MarketDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=JacksonAutoConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class H5JsonContractTest {
    @Autowired JsonMapper json;
    private final Instant time=Instant.parse("2026-10-04T00:00:00Z");
    private JsonNode tree(Object dto) throws Exception { return json.readTree(json.writeValueAsString(dto)); }

    @Test void alertDecimalsRoundTripWithoutExponentOrJavascriptRounding() throws Exception {
        var rule=new AlertDtos.Rule(1,"alert","BTCUSDT","CHANNEL_OUTSIDE",new BigDecimal("0.00000001"),
                new BigDecimal("100.123456789012345677"),new BigDecimal("100.123456789012345678"),"ONCE","1h",null,true,0,null,time);
        var result=tree(rule);
        assertThat(result.path("target").isString()).isTrue();
        assertThat(result.path("target").asString()).isEqualTo("0.00000001");
        assertThat(result.path("lower").asString()).isEqualTo("100.123456789012345677");
        assertThat(result.path("upper").asString()).isEqualTo("100.123456789012345678");
    }

    @Test void alertDecimalsDropInsignificantTrailingZeros() throws Exception {
        var rule=new AlertDtos.Rule(1,"alert","BTCUSDT","CHANNEL_OUTSIDE",null,
                new BigDecimal("79705.620000000000000000"),new BigDecimal("0.000000000000000000"),"ONCE","1h",null,true,0,null,time);
        var result=tree(rule);
        assertThat(result.path("lower").asString()).isEqualTo("79705.62");
        assertThat(result.path("upper").asString()).isEqualTo("0");
        var event=tree(new AlertDtos.Event(1,2L,"PRICE_ALERT_TRIGGERED","Alert","Body","BTCUSDT",
                new BigDecimal("78000.000000000000000000"),time,time));
        assertThat(event.path("price").asString()).isEqualTo("78000");
    }

    @Test void marketDirectoryAndTickerExposeFieldsConsumedByTheH5Page() throws Exception {
        var symbol=tree(new MarketDtos.Symbol("BTCUSDT","BTC","USDT"));
        assertThat(symbol.path("baseAsset").asString()).isEqualTo("BTC");
        assertThat(symbol.path("quoteAsset").asString()).isEqualTo("USDT");
        var ticker=tree(new MarketDtos.Ticker("BTCUSDT",new BigDecimal("100"),BigDecimal.ONE,BigDecimal.TEN,time));
        assertThat(ticker.path("lastPrice").decimalValue()).isEqualByComparingTo("100");
        assertThat(ticker.path("changePercent").decimalValue()).isEqualByComparingTo("1");
        assertThat(ticker.path("updatedAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
        var candle=tree(new MarketDtos.Candle(1000,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.ONE,1999));
        assertThat(candle.path("openTime").asLong()).isEqualTo(1000);
        assertThat(candle.path("closeTime").asLong()).isEqualTo(1999);
    }

    @Test void alertExpiryFromH5IsNotSilentlyIgnored() throws Exception {
        var request=json.readValue("""
                {"name":"alert","symbol":"BTCUSDT","condition":"ABOVE","target":"100",
                "frequency":"ONCE","interval":"1h","expiresAt":"2026-10-04T00:00:00Z"}
                """,AlertDtos.Create.class);
        assertThat(request.expiresAt()).isEqualTo(time);
    }

    @Test void alertPaginationAndEventDatesMatchTheH5Contract() throws Exception {
        var rule=new AlertDtos.Rule(1,"alert","BTCUSDT","ABOVE",BigDecimal.ONE,null,null,"ONCE","1h",time,true,2,time,time);
        var page=tree(new AlertDtos.Page<>(List.of(rule),1,0,20,false));
        assertThat(page.has("hasMore")).isTrue();
        assertThat(page.path("items").get(0).path("triggeredCount").asLong()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("expiresAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
        assertThat(page.path("items").get(0).path("lastTriggeredAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
        assertThat(page.path("items").get(0).path("createdAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
        var event=tree(new AlertDtos.Event(1,2L,"PRICE_ALERT_TRIGGERED","Alert","Body","BTCUSDT",BigDecimal.ONE,time,time));
        assertThat(event.path("ruleId").asLong()).isEqualTo(2);
        assertThat(event.path("occurredAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
        assertThat(event.path("readAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
    }

    @Test void webhookIpRestrictionsAndActivationFieldsMatchH5() throws Exception {
        var request=json.readValue("""
                {"name":"hook","description":"d","keywords":"BTC","allowedIps":"192.0.2.1"}
                """,WebhookService.Draft.class);
        assertThat(request.allowedIps()).isEqualTo("192.0.2.1");
        var activated=tree(new WebhookService.Activation("public-id","key"));
        assertThat(activated.path("publicId").asString()).isEqualTo("public-id");
        assertThat(activated.path("signingKey").asString()).isEqualTo("key");
        var rule=tree(new WebhookService.Rule(1,"hook","d","BTC","192.0.2.1","ACTIVE","public-id"));
        assertThat(rule.path("allowedIps").asString()).isEqualTo("192.0.2.1");
        assertThat(rule.path("publicId").asString()).isEqualTo("public-id");
    }

    @Test void indexHistoryDatesAndSourceMatchH5() throws Exception {
        var history=tree(new FearGreedService.History(List.of(new FearGreedService.Point(20,"Fear",time)),time,false,"source"));
        assertThat(history.path("fetchedAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
        assertThat(history.path("sourceUrl").asString()).isEqualTo("source");
        assertThat(history.path("points").get(0).path("observedAt").asString()).isEqualTo("2026-10-04T00:00:00Z");
    }

    @Test void subscriptionThresholdsAndProfileColorsCanBeSavedFromH5() throws Exception {
        var subscription=json.readValue("{\"enabled\":true,\"fearThreshold\":25,\"greedThreshold\":75}",StrategySubscriptionService.Settings.class);
        assertThat(subscription.fearThreshold()).isEqualTo(25);
        assertThat(subscription.greedThreshold()).isEqualTo(75);
        assertThat(tree(subscription).path("greedThreshold").asInt()).isEqualTo(75);
        var profile=json.readValue("{\"name\":\"Alice\",\"country\":\"HK\",\"introduction\":\"Hello\",\"upDownColor\":\"RED_UP\"}",UserProfileService.Profile.class);
        assertThat(profile.upDownColor()).isEqualTo("RED_UP");
        assertThat(tree(profile).path("upDownColor").asString()).isEqualTo("RED_UP");
    }

    @Test void legacyProfileTimestampKeepsItsExistingSnakeCaseContract() throws Exception {
        var profile=tree(new UserDtos.UserResponse(1,"Alice","alice@example.com",time));
        assertThat(profile.path("created_at").asString()).isEqualTo("2026-10-04T00:00:00Z");
        assertThat(profile.has("createdAt")).isFalse();
    }
}
