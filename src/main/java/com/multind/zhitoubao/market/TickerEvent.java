package com.multind.zhitoubao.market;

import java.math.BigDecimal;
import java.time.Instant;

public record TickerEvent(String symbol, BigDecimal price, Instant eventTime) {}
