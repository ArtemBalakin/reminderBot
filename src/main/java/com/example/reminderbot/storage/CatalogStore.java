package com.example.reminderbot.storage;

import com.example.reminderbot.model.Catalog;

public interface CatalogStore {
    Catalog load();
    void save(Catalog catalog);
}
