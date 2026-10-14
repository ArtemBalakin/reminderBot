package com.example.reminderbot.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProfileProgressTest {
    @Test
    void streakContinuesFromYesterdayAndStopsAtTheFirstGap() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        var result = ProfileProgress.summarize(today, "week", Map.of(today.minusDays(1), 3, today.minusDays(2), 1, today.minusDays(4), 2));
        assertEquals(2, result.get("streak"));
        assertEquals(6, result.get("total"));
    }

    @Test
    void monthIncludesZeroDaysAndIgnoresOtherMonthsAndFutureRecords() {
        LocalDate today = LocalDate.of(2024, 2, 15);
        var result = ProfileProgress.summarize(today, "month", Map.of(today, 2, today.minusMonths(1), 5, today.plusDays(1), 8));
        assertEquals(2, result.get("completedMonth"));
        assertEquals(2, result.get("total"));
        assertEquals(29, ((List<?>) result.get("series")).size());
    }

    @Test
    void yearBucketsSumByMonthAndIncludeEmptyMonths() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        var result = ProfileProgress.summarize(today, "year", Map.of(LocalDate.of(2026,1,1), 2, LocalDate.of(2026,1,31), 3, today, 1));
        var series = (List<?>) result.get("series");
        assertEquals(12, series.size());
        assertEquals(5, ((Map<?, ?>) series.get(0)).get("count"));
        assertEquals(6, result.get("total"));
    }

    @Test
    void emptyHistoryHasNoStreakOrCompletions() {
        var result = ProfileProgress.summarize(LocalDate.of(2026,1,1), "week", Map.of());
        assertEquals(0, result.get("streak"));
        assertEquals(0, result.get("total"));
        assertEquals(7, ((List<?>) result.get("series")).size());
    }
}
