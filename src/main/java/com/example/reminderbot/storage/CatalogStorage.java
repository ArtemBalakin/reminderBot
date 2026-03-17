package com.example.reminderbot.storage;

import com.example.reminderbot.model.CatalogFile;
import com.example.reminderbot.model.TaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class CatalogStorage {
    private final Path filePath;
    private final ObjectMapper mapper;
    private volatile Map<String, TaskDefinition> cache = new LinkedHashMap<>();

    public CatalogStorage(Path filePath) {
        this.filePath = filePath;
        this.mapper = JsonMapperFactory.create();
    }

    public synchronized Map<String, TaskDefinition> load() {
        try {
            if (Files.notExists(filePath)) {
                Files.createDirectories(filePath.getParent());
                save(new CatalogFile(java.util.List.of()));
            }

            CatalogFile file = mapper.readValue(filePath.toFile(), CatalogFile.class);
            LinkedHashMap<String, TaskDefinition> loaded = new LinkedHashMap<>();
            for (TaskDefinition task : file.tasks()) {
                loaded.put(task.id(), task);
            }
            this.cache = loaded;
            return new LinkedHashMap<>(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load catalog from " + filePath, e);
        }
    }

    public synchronized void save(CatalogFile catalogFile) {
        try {
            Files.createDirectories(filePath.getParent());
            Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            mapper.writeValue(temp.toFile(), catalogFile);
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            load();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save catalog to " + filePath, e);
        }
    }

    public synchronized CatalogFile parse(String json) {
        try {
            return mapper.readValue(json, CatalogFile.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось распарсить JSON каталога: " + e.getMessage(), e);
        }
    }

    public Map<String, TaskDefinition> current() {
        return new LinkedHashMap<>(cache);
    }
}
