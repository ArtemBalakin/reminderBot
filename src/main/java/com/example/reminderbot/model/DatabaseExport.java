package com.example.reminderbot.model;

public record DatabaseExport(
        Catalog catalog,
        BotState state
) {
}
