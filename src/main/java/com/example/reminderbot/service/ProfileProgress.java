package com.example.reminderbot.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Calendar buckets are in the user's timezone, including days with no completions. */
public final class ProfileProgress {
    private ProfileProgress() {}

    public static LocalDate periodStart(LocalDate today, String period) {
        return switch (period) {
            case "week" -> today.minusDays(6);
            case "year" -> today.withDayOfYear(1);
            default -> today.withDayOfMonth(1);
        };
    }

    public static Map<String, Object> summarize(LocalDate today, String period, Map<LocalDate, Integer> counts) {
        LocalDate cursor = counts.getOrDefault(today, 0) > 0 ? today : today.minusDays(1);
        int streak = 0;
        while (counts.getOrDefault(cursor, 0) > 0) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        int completedMonth = counts.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(today.withDayOfMonth(1)) && !e.getKey().isAfter(today))
                .mapToInt(Map.Entry::getValue).sum();
        LocalDate start = periodStart(today, period);
        LocalDate end = "year".equals(period) ? today.withMonth(12).withDayOfMonth(31)
                : "week".equals(period) ? today : today.withDayOfMonth(today.lengthOfMonth());
        List<Map<String, Object>> series = new ArrayList<>();
        int total = 0;
        for (LocalDate date = start; !date.isAfter(end); date = "year".equals(period) ? date.plusMonths(1) : date.plusDays(1)) {
            LocalDate bucketStart = date;
            LocalDate bucketEnd = "year".equals(period) ? date.plusMonths(1) : date.plusDays(1);
            int count = counts.entrySet().stream()
                    .filter(e -> !e.getKey().isBefore(bucketStart) && e.getKey().isBefore(bucketEnd) && !e.getKey().isAfter(today))
                    .mapToInt(Map.Entry::getValue).sum();
            series.add(Map.of("date", date.toString(), "count", count));
            total += count;
        }
        return Map.of("streak", streak, "completedMonth", completedMonth, "series", series, "total", total);
    }
}
