package com.multind.bitpongo.exchange;

import org.springframework.stereotype.Component;

@Component
public class CredentialMasker {
    public String maskAccessKey(String value) {
        if (value == null || value.isEmpty()) return value;
        if (value.length() <= 3) return "*".repeat(value.length());
        return value.substring(0, 3) + "*".repeat(value.length() - 3);
    }

    public String maskDetail(String value) {
        if (value == null || value.isEmpty()) return value;
        if (value.length() <= 8) return "*".repeat(value.length());
        return value.substring(0, 4) + "********" + value.substring(value.length() - 4);
    }

    public boolean isPlaceholder(String value) {
        return value != null && value.indexOf('*') >= 0;
    }
}
