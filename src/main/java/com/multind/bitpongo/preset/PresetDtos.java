package com.multind.bitpongo.preset;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class PresetDtos {
    private PresetDtos() {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Preset(
            String key,
            String type,
            String name,
            String author,
            String authorAvatar,
            boolean official,
            boolean recommended,
            long followers,
            boolean favorite,
            String totalRevenue,
            String totalRatio,
            String exchange,
            List<String> symbols,
            Long runtimeDays,
            List<String> sparkline,
            Map<String, Object> params) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Page(List<Preset> items, int page, int size, long total, boolean hasMore) {}

    public record Favorite(boolean favorite, long followers) {}
    public record Visibility(@JsonProperty("public") boolean visible) {}
    public record VisibilityRequest(@JsonProperty("public") boolean visible) {}
}
