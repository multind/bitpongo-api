package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.FearGreedArchive;
import com.multind.bitpongo.market.FearGreedClient;
import com.multind.bitpongo.market.FearGreedService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FearGreedArchiveIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired FearGreedArchive archive;

    @Test void restartUsesPersistedHistoryAsStaleWithoutGeneratingSignals() {
        jdbc.update("delete from fear_greed_history");
        Instant now=Instant.now();
        var client=mock(FearGreedClient.class);
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(67,"Greed",now.minusSeconds(3600))));
        var service=new FearGreedService(client,Clock.fixed(now,ZoneOffset.UTC),archive);
        assertThat(service.history(30).stale()).isFalse();
        when(client.history()).thenThrow(new IllegalStateException("offline"));
        var restarted=new FearGreedService(client,Clock.fixed(now.plusSeconds(301),ZoneOffset.UTC),archive);
        var history=restarted.history(30);
        assertThat(history.stale()).isTrue();
        assertThat(history.points()).extracting(FearGreedService.Point::value).containsExactly(67);
        assertThat(history.fetchedAt()).isBetween(now.minusMillis(1),now.plusMillis(1));
        assertThatThrownBy(restarted::latestFresh).hasMessageContaining("过期");
    }

    @Test void refreshUpsertsCorrectionsAndRetainsOlderObservations() {
        jdbc.update("delete from fear_greed_history");
        Instant now=Instant.now(); var client=mock(FearGreedClient.class);
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(67,"Greed",now.minusSeconds(3600)),new FearGreedService.Point(40,"Fear",now.minusSeconds(90000))));
        new FearGreedService(client,Clock.fixed(now,ZoneOffset.UTC),archive).history(30);
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(68,"Greed",now.minusSeconds(3600))));
        new FearGreedService(client,Clock.fixed(now.plusSeconds(301),ZoneOffset.UTC),archive).history(30);
        when(client.history()).thenThrow(new IllegalStateException("offline"));
        var restarted=new FearGreedService(client,Clock.fixed(now.plusSeconds(302),ZoneOffset.UTC),archive);
        assertThat(restarted.history(30).points()).extracting(FearGreedService.Point::value).containsExactly(68,40);
        assertThat(jdbc.queryForObject("select count(*) from fear_greed_history",Integer.class)).isEqualTo(2);
    }
}
