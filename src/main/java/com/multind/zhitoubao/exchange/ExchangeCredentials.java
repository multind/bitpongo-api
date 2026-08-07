package com.multind.zhitoubao.exchange;

public record ExchangeCredentials(String accessKey, String secretKey, String password) {
    public ExchangeCredentials {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("交易所密钥不能为空");
        }
    }
}
