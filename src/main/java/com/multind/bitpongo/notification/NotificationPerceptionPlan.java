package com.multind.bitpongo.notification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selects the eligible channels and groups equal strengths into one delivery wave. */
final class NotificationPerceptionPlan {
    enum Strength { WEAK, MODERATE_WEAK, MODERATE, MODERATE_STRONG, STRONG }

    record Channel(String code, Strength strength, boolean eligible) {}

    private NotificationPerceptionPlan() {}

    static List<List<String>> stages(List<Channel> channels, Set<String> selected, boolean escalate) {
        var all = new LinkedHashSet<String>();
        var byStrength = new EnumMap<Strength, List<String>>(Strength.class);
        for (Channel channel : channels) {
            if (!channel.eligible() || (selected != null && !selected.contains(channel.code())) || !all.add(channel.code())) {
                continue;
            }
            if (escalate) byStrength.computeIfAbsent(channel.strength(), ignored -> new ArrayList<>()).add(channel.code());
        }
        if (!escalate) return all.isEmpty() ? List.of() : List.of(List.copyOf(all));
        return byStrength.values().stream().map(List::copyOf).toList();
    }
}
