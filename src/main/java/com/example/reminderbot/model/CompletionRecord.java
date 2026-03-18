package com.example.reminderbot.model;

import java.time.Instant;

public record CompletionRecord(
        String promptId,
        String subscriptionId,
        String taskId,
        long chatId,
        Instant scheduledFor,
        Instant completedAt
) {
}
