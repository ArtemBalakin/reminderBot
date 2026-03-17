package com.example.reminderbot.model;

public record RegisteredUser(
        long chatId,
        String username,
        String firstName,
        String zoneId
) {
}
