package com.multind.bitpongo.alerts;

import com.multind.bitpongo.market.MarketDataService;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class PriceAlertSampleTest {
    @Test void rejectsSamplesBeforeRuleCreationWithoutWritingState() {
        var jdbc=mock(JdbcTemplate.class);
        var row=new HashMap<String,Object>();
        row.put("created_at",PriceAlertService.utc(Instant.parse("2026-10-03T12:00:00Z")));
        when(jdbc.queryForList(contains("from user"),anyLong())).thenReturn(List.of(java.util.Map.of("id",1)));
        when(jdbc.queryForList(contains("from price_alert"),anyLong(),anyLong())).thenReturn(List.of(row));
        var service=new PriceAlertService(jdbc,mock(MarketDataService.class),mock(AlertNotificationBridge.class));
        assertThatCode(()->service.sample(1,1,BigDecimal.TEN,Instant.parse("2026-10-03T11:00:00Z"),1)).doesNotThrowAnyException();
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
