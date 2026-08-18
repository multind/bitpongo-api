package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationApplicationService {
    private final DictRepository dictionaries;
    private final DingTalkClient dingTalk;
    private final Clock clock;

    @Autowired
    public NotificationApplicationService(ObjectProvider<DictRepository> dictionaries, DingTalkClient dingTalk) {
        this(dictionaries.getIfAvailable(), dingTalk, Clock.systemDefaultZone());
    }

    NotificationApplicationService(DictRepository dictionaries, DingTalkClient dingTalk, Clock clock) {
        this.dictionaries = dictionaries; this.dingTalk = dingTalk; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public String notices() {
        if (dictionaries == null) throw new BusinessException(503, "通知配置暂不可用");
        return dictionaries.findFirstByCode("notify_method_init")
                .map(DictEntity::getValue)
                .orElseThrow(() -> new BusinessException(404, "通知配置不存在"));
    }

    public Map<String, Object> testDing(String webhook, String signed) {
        String time = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String content = """
                ### 智投宝通知
                - **时间**: %s
                - **内容**: 恭喜您！当您收到这条消息时，表示您已配置正确！
                """.formatted(time);
        return dingTalk.sendMarkdown(webhook, signed, "智投宝通知", content);
    }
}
