package com.example.reminderbot.model;

import java.util.HashMap;
import java.util.Map;

public record UserSession(
        SessionType type,
        Map<String, String> data,
        Integer helperMessageId
) {
    public UserSession {
        data = data == null ? new HashMap<>() : new HashMap<>(data);
    }

    public static UserSession of(SessionType type) {
        return new UserSession(type, new HashMap<>(), null);
    }
}
