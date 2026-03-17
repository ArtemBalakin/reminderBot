package com.example.reminderbot.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record BotState(
        Map<Long, UserProfile> users,
        List<Subscription> subscriptions,
        List<ActivePrompt> prompts,
        Map<Long, UserSession> sessions,
        long lastUpdateId
) {
    public BotState {
        users = users == null ? new HashMap<>() : new HashMap<>(users);
        subscriptions = subscriptions == null ? new ArrayList<>() : new ArrayList<>(subscriptions);
        prompts = prompts == null ? new ArrayList<>() : new ArrayList<>(prompts);
        sessions = sessions == null ? new HashMap<>() : new HashMap<>(sessions);
    }

    public static BotState empty() {
        return new BotState(new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), 0L);
    }
}
