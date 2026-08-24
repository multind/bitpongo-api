package com.multind.bitpongo.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class NotificationMessageRenderer {

    private static final int MAX_ERROR_CODE_POINTS = 300;
    private static final Pattern URI = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+\\S+");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final String CREDENTIAL_NAME =
            "(?:(?:[a-z0-9]+[_-])*(?:access(?:[_-]?(?:key|token))?"
                    + "|secret(?:[_-]?key)?|device[_-]?key|token|key)"
                    + "|(?:api|client|refresh)[_-]?(?:key|secret|token))";
    private static final Pattern ASSIGNED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(" + CREDENTIAL_NAME + ")"
                    + "\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern BARE_CREDENTIAL = Pattern.compile(
            "(?i)\\b(" + CREDENTIAL_NAME + ")"
                    + "\\s+(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss VV", Locale.ROOT);

    private final BarkEventPolicy policies;

    public NotificationMessageRenderer(BarkEventPolicy policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    public BarkMessage render(
            NotificationEvent event,
            String locale,
            String timezone,
            String url) {
        Objects.requireNonNull(event, "event");
        Language language = Language.from(locale);
        ZoneId zone = ZoneId.of(timezone);
        BarkEventPolicy.Policy policy = policies.policy(event);

        return new BarkMessage(
                language.title(event.type()),
                body(event, language, zone),
                policy.level(),
                policy.volume(),
                policy.call(),
                policy.sound(),
                policy.group(),
                url);
    }

    private static String body(NotificationEvent event, Language language, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        if (event.occurredAt() != null) {
            lines.add(language.time + TIME.format(event.occurredAt().atZone(zone)));
        }
        add(lines, language.userId, event.userId());
        add(lines, language.planId, event.planId());
        add(lines, language.intentId, event.intentId());

        Map<String, Object> attributes = event.attributes() == null ? Map.of() : event.attributes();
        Object symbols = attributes.containsKey("symbol")
                ? attributes.get("symbol")
                : joinedSymbols(attributes.get("symbols"));
        addSanitized(lines, language.symbol, symbols);
        Object status = attributes.containsKey("status")
                ? attributes.get("status")
                : attributes.get("resultStatus");
        addSanitized(lines, language.result, status);
        Object error = attributes.containsKey("error")
                ? attributes.get("error")
                : attributes.get("errorSummary");
        if (error != null) {
            lines.add(language.error + sanitizeError(String.valueOf(error)));
        }
        return String.join("\n", lines);
    }

    private static String joinedSymbols(Object value) {
        if (!(value instanceof List<?> values)) {
            return null;
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .limit(50)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void add(List<String> lines, String label, Object value) {
        if (value != null) {
            lines.add(label + value);
        }
    }

    private static void addSanitized(List<String> lines, String label, Object value) {
        if (value != null) {
            lines.add(label + redact(String.valueOf(value)));
        }
    }

    public static String sanitizeError(String error) {
        String redacted = redact(error);
        int[] codePoints = redacted.codePoints().limit(MAX_ERROR_CODE_POINTS).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private static String redact(String value) {
        String redacted = URI.matcher(value).replaceAll("<redacted-uri>");
        redacted = EMAIL.matcher(redacted).replaceAll("<redacted-email>");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer <redacted>");
        redacted = ASSIGNED_CREDENTIAL.matcher(redacted).replaceAll("$1=<redacted>");
        return BARE_CREDENTIAL.matcher(redacted).replaceAll("$1 <redacted>");
    }

    private enum Language {
        ZH_CN(
                "时间：", "用户 ID：", "计划 ID：", "订单 Intent ID：",
                "币种：", "结果：", "错误："),
        ZH_TW(
                "時間：", "使用者 ID：", "計畫 ID：", "訂單 Intent ID：",
                "幣種：", "結果：", "錯誤："),
        EN_US(
                "Time: ", "User ID: ", "Plan ID: ", "Order Intent ID: ",
                "Symbol: ", "Result: ", "Error: ");

        private final String time;
        private final String userId;
        private final String planId;
        private final String intentId;
        private final String symbol;
        private final String result;
        private final String error;

        Language(
                String time,
                String userId,
                String planId,
                String intentId,
                String symbol,
                String result,
                String error) {
            this.time = time;
            this.userId = userId;
            this.planId = planId;
            this.intentId = intentId;
            this.symbol = symbol;
            this.result = result;
            this.error = error;
        }

        private static Language from(String locale) {
            return switch (locale) {
                case "zh-TW" -> ZH_TW;
                case "en-US" -> EN_US;
                default -> ZH_CN;
            };
        }

        private String title(NotificationEventType type) {
            return switch (this) {
                case ZH_CN -> switch (type) {
                    case SCHEDULER_FATAL -> "调度器严重故障";
                    case ORDER_MANUAL_REVIEW -> "订单需要人工处理";
                    case TRADE_FAILED -> "交易失败";
                    case MARKET_OUTAGE -> "行情服务不可用";
                    case PLAN_EXECUTION_SKIPPED -> "计划执行已跳过";
                    case TRADE_SUCCEEDED -> "交易成功";
                    case ASSET_SNAPSHOT_FAILED -> "资产快照失败";
                    case SYSTEM_RECOVERED -> "系统已恢复";
                    case SERVICE_STARTED -> "服务已启动";
                    case BARK_TEST -> "Bark 测试";
                };
                case ZH_TW -> switch (type) {
                    case SCHEDULER_FATAL -> "排程器嚴重故障";
                    case ORDER_MANUAL_REVIEW -> "訂單需要人工處理";
                    case TRADE_FAILED -> "交易失敗";
                    case MARKET_OUTAGE -> "行情服務無法使用";
                    case PLAN_EXECUTION_SKIPPED -> "計畫執行已略過";
                    case TRADE_SUCCEEDED -> "交易成功";
                    case ASSET_SNAPSHOT_FAILED -> "資產快照失敗";
                    case SYSTEM_RECOVERED -> "系統已恢復";
                    case SERVICE_STARTED -> "服務已啟動";
                    case BARK_TEST -> "Bark 測試";
                };
                case EN_US -> switch (type) {
                    case SCHEDULER_FATAL -> "Critical scheduler failure";
                    case ORDER_MANUAL_REVIEW -> "Order needs manual review";
                    case TRADE_FAILED -> "Trade failed";
                    case MARKET_OUTAGE -> "Market data unavailable";
                    case PLAN_EXECUTION_SKIPPED -> "Plan execution skipped";
                    case TRADE_SUCCEEDED -> "Trade succeeded";
                    case ASSET_SNAPSHOT_FAILED -> "Asset snapshot failed";
                    case SYSTEM_RECOVERED -> "System recovered";
                    case SERVICE_STARTED -> "Service started";
                    case BARK_TEST -> "Bark test";
                };
            };
        }
    }
}
