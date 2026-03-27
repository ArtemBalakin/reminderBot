package com.example.reminderbot.model;

import java.time.Instant;

import java.util.List;

public record Subscription(
        String id,
        String taskId,
        long chatId,
        List<String> dailyTimes,
        String dayOfWeek,
        Integer dayOfMonth,
        Instant nextRunAt,
        boolean active,
        boolean oneTimeDone,
        List<String> daysOfWeek,
        List<Integer> daysOfMonth
) {
    public Subscription {
        dailyTimes = dailyTimes == null ? List.of() : List.copyOf(dailyTimes);
        
        List<String> dws = daysOfWeek;
        if (dws == null) {
            dws = dayOfWeek != null ? List.of(dayOfWeek) : List.of();
        }
        daysOfWeek = List.copyOf(dws);
        
        List<Integer> dms = daysOfMonth;
        if (dms == null) {
            dms = dayOfMonth != null ? List.of(dayOfMonth) : List.of();
        }
        daysOfMonth = List.copyOf(dms);
    }
}
