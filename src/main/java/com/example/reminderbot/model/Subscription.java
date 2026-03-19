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
        boolean oneTimeDone,
        List<String> daysOfWeek,
        List<Integer> daysOfMonth
) {
    public Subscription {
        dailyTimes = dailyTimes == null ? new ArrayList<>() : new ArrayList<>(dailyTimes);
        
        List<String> dws = daysOfWeek;
        if (dws == null) {
            dws = dayOfWeek != null ? List.of(dayOfWeek) : new ArrayList<>();
        }
        daysOfWeek = new ArrayList<>(dws);
        
        List<Integer> dms = daysOfMonth;
        if (dms == null) {
            dms = dayOfMonth != null ? List.of(dayOfMonth) : new ArrayList<>();
        }
        daysOfMonth = new ArrayList<>(dms);
    }
}
