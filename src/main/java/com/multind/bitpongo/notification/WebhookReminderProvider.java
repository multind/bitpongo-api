package com.multind.bitpongo.notification;

import java.net.URI;
import java.util.regex.Pattern;

enum WebhookReminderProvider {
    DINGTALK("oapi.dingtalk.com", "/robot/send", "access_token=[A-Za-z0-9_-]+"),
    FEISHU("open.feishu.cn", "/open-apis/bot/v2/hook/[A-Za-z0-9_-]+", null),
    SLACK("hooks.slack.com", "/services/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+", null),
    DISCORD("discord.com", "/api/(?:v[0-9]+/)?webhooks/[0-9]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*", null);

    private final String host;
    private final Pattern path;
    private final Pattern query;

    WebhookReminderProvider(String host, String path, String query) {
        this.host = host;
        this.path = Pattern.compile(path);
        this.query = query == null ? null : Pattern.compile(query);
    }

    URI validate(String raw) {
        if (raw == null || raw.length() > 2048) throw invalid();
        final URI url;
        try {
            url = URI.create(raw);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || !host.equalsIgnoreCase(url.getHost())
                || url.getRawUserInfo() != null || url.getRawFragment() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !path.matcher(url.getRawPath()).matches()) throw invalid();
        String value = url.getRawQuery();
        if (query == null ? value != null : value == null || !query.matcher(value).matches()) throw invalid();
        return url;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid webhook destination");
    }
}
