package com.example.reminderbot.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class JsonStore<T> {
    private final Path path;
    private final Class<T> type;
    private final T emptyValue;
    private final ObjectMapper mapper;

    public JsonStore(Path path, Class<T> type, T emptyValue) {
        this.path = path;
        this.type = type;
        this.emptyValue = emptyValue;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized T load() {
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                save(emptyValue);
                return emptyValue;
            }
            return mapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + path, e);
        }
    }

    public synchronized void save(T data) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            mapper.writeValue(temp.toFile(), data);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save " + path, e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
