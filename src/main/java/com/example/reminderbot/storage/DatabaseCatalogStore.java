package com.example.reminderbot.storage;

import com.example.reminderbot.model.Catalog;

public class DatabaseCatalogStore implements Store<Catalog> {
    private final DatabaseStore databaseStore;

    public DatabaseCatalogStore(DatabaseStore databaseStore) {
        this.databaseStore = databaseStore;
    }

    @Override
    public Catalog load() {
        return databaseStore.loadCatalog();
    }

    @Override
    public void save(Catalog catalog) {
        databaseStore.saveCatalog(catalog);
    }

    @Override
    public com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return databaseStore.mapper();
    }
}
