package com.example.reminderbot.model;

public record TaskDefinition(
        String id,
        String title,
        TaskKind kind,
        ScheduleRule schedule,
        Integer recommendedSlots,
        String note
) {
}
