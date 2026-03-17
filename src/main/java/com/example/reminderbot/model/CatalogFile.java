package com.example.reminderbot.model;

import java.util.ArrayList;
import java.util.List;

public record CatalogFile(
        List<TaskDefinition> tasks
) {
    public CatalogFile {
        tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
    }
}
