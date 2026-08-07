package com.multind.zhitoubao.exchange;

import java.math.BigDecimal;

public record AccountBalance(String asset, BigDecimal free, BigDecimal locked) {}
