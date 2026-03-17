package com.example.reminderbot.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record Subscription(
        String id,
        String taskId,
        long chatId,
        List<String> dailyTimes,
        String dayOfWeek,
        Integer dayOfMonth,
        String zoneId,
        Instant nextRunAt,
        boolean active,
        boolean oneTimeDone
) {
    public Subscription {
        dailyTimes = dailyTimes == null ? new ArrayList<>() : new ArrayList<>(dailyTimes);
    }
}
