package com.example.reminderbot.model;

import java.util.ArrayList;
import java.util.List;

public record Catalog(
        List<TaskDefinition> tasks
) {
    public Catalog {
        tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
    }

    public static Catalog empty() {
        return new Catalog(new ArrayList<>());
    }
}
