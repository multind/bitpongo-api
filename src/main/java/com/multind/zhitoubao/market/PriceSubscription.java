package com.multind.zhitoubao.market;

import java.util.List;

public record PriceSubscription(String action, List<String> symbols, String exchange) {}
