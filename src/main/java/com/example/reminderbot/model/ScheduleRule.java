package com.example.reminderbot.model;

public record ScheduleRule(
        FrequencyUnit unit,
        int interval
) {
}
