package com.multind.zhitoubao.notification;

import java.util.Map;

public interface DingTalkClient {
    Map<String, Object> sendMarkdown(
            String webhook, String secret, String title, String content);
}
