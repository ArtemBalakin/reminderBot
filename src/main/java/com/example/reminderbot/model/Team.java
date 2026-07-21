package com.example.reminderbot.model;

public record Team(
        String id,
        String name,
        long ownerChatId,
        boolean active
) {
}
