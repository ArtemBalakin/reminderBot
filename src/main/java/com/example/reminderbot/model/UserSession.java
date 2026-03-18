package com.example.reminderbot.model;

public record UserSession(
        SessionType type,
        String targetId
) {
}
