package com.example.reminderbot.model;

import java.time.Instant;

public record ActivePrompt(
        String id,
        String subscriptionId,
        String taskId,
        long chatId,
        Instant scheduledFor,
        Instant nextPingAt,
        String state,
        Integer messageId,
        int alertBroadcastCount,
        boolean endOfDayAlertSent
) {
}
