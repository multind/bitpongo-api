package com.multind.bitpongo.market;

import java.util.List;

public record PriceSubscription(String action, List<String> symbols, String exchange) {}
