package com.multind.zhitoubao.market;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketPrice(BigDecimal price, Instant updatedAt) {}
