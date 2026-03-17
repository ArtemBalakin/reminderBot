package com.example.reminderbot.model;

import java.time.Instant;

public record PendingPrompt(
        String id,
        String subscriptionId,
        String taskId,
        long chatId,
        Instant dueAt,
        Integer telegramMessageId,
        PromptStatus status
) {
}
