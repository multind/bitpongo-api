package com.multind.bitpongo.market;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FearGreedServiceTest {
    private final Instant now=Instant.parse("2026-10-04T12:00:00Z");
    @Test void cachesHistoryAndReturnsRequestedBoundWithoutAnotherFetch() {
        var client=mock(FearGreedClient.class);
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(67,"Greed",now.minusSeconds(3600)),new FearGreedService.Point(40,"Fear",now.minusSeconds(90000))));
        var service=new FearGreedService(client,Clock.fixed(now,ZoneOffset.UTC));
        assertThat(service.history(1).points()).hasSize(1);
        assertThat(service.history(30).points()).hasSize(2);
        assertThat(service.latestFresh().value()).isEqualTo(67);
        verify(client,times(1)).history();
    }
    @Test void oldDataIsVisibleAsStaleButNeverUsableForSignals() {
        var client=mock(FearGreedClient.class);
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(40,"Fear",now.minusSeconds(172801))));
        var service=new FearGreedService(client,Clock.fixed(now,ZoneOffset.UTC));
        assertThat(service.history(30).stale()).isTrue();
        assertThatThrownBy(service::latestFresh).hasMessageContaining("过期");
    }
    @Test void invalidHistoryAndBoundsFailInsteadOfShowingDefaultZero() {
        var client=mock(FearGreedClient.class);
        var service=new FearGreedService(client,Clock.fixed(now,ZoneOffset.UTC));
        assertThatThrownBy(()->service.history(0)).hasMessageContaining("条数");
        assertThatThrownBy(()->service.history(366)).hasMessageContaining("条数");
        when(client.history()).thenReturn(List.of());
        assertThatThrownBy(()->service.history(30)).hasMessageContaining("不可用");
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(101,"Greed",now)));
        assertThatThrownBy(()->service.history(30)).hasMessageContaining("格式");
    }
    @Test void refreshFailureMarksCachedHistoryStaleAndBlocksSignalGeneration() {
        var client=mock(FearGreedClient.class);var clock=mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        when(client.history()).thenReturn(List.of(new FearGreedService.Point(67,"Greed",now.minusSeconds(3600))));
        var service=new FearGreedService(client,clock);service.history(30);
        when(clock.instant()).thenReturn(now.plusSeconds(301));
        when(client.history()).thenThrow(new IllegalStateException("provider offline"));
        assertThat(service.history(30).stale()).isTrue();
        assertThatThrownBy(service::latestFresh).hasMessageContaining("过期");
    }
}
