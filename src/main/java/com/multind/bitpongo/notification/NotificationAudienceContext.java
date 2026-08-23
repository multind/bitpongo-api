package com.multind.bitpongo.notification;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record NotificationAudienceContext(Set<Long> recipientUserIds, boolean admin) {

    public NotificationAudienceContext {
        Objects.requireNonNull(recipientUserIds, "recipientUserIds");
        LinkedHashSet<Long> copy = new LinkedHashSet<>();
        for (Long userId : recipientUserIds) {
            if (userId == null) {
                throw new IllegalArgumentException("recipient user id must not be null");
            }
            if (userId < 0) {
                throw new IllegalArgumentException("recipient user id must not be negative");
            }
            copy.add(userId);
        }
        recipientUserIds = Collections.unmodifiableSet(copy);
    }
}
