package com.example.reminderbot.storage;

import com.example.reminderbot.model.BotState;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class StateStorage {
    private final Path filePath;
    private final ObjectMapper mapper;

    public StateStorage(Path filePath) {
        this.filePath = filePath;
        this.mapper = JsonMapperFactory.create();
    }

    public synchronized BotState load() {
        try {
            if (Files.notExists(filePath)) {
                Files.createDirectories(filePath.getParent());
                BotState empty = BotState.empty();
                save(empty);
                return empty;
            }
            return mapper.readValue(filePath.toFile(), BotState.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load state from " + filePath, e);
        }
    }

    public synchronized void save(BotState state) {
        try {
            Files.createDirectories(filePath.getParent());
            Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            mapper.writeValue(temp.toFile(), state);
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save state to " + filePath, e);
        }
    }
}
