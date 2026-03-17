package com.example.reminderbot.telegram;

public record CallbackData(String action, String payload) {
    public static CallbackData parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty callback data");
        }
        String[] parts = raw.split("\\|", 2);
        if (parts.length == 1) {
            return new CallbackData(parts[0], "");
        }
        return new CallbackData(parts[0], parts[1]);
    }

    public static String build(String action, String payload) {
        return payload == null || payload.isBlank() ? action : action + "|" + payload;
    }
}
