package com.example.reminderbot.model;

public record TeamJoinRequest(
        String id,
        String teamId,
        long chatId
) {
}
