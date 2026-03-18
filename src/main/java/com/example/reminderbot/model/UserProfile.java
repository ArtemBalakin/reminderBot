package com.example.reminderbot.model;

public record UserProfile(
        long chatId,
        String username,
        String firstName,
        String zoneId,
        boolean alertsEnabled
) {
}
