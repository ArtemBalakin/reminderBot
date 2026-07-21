package com.example.reminderbot.model;

public record TeamMember(
        String teamId,
        long chatId,
        TeamRole role
) {
}
