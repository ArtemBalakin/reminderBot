package com.example.reminderbot.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class JsonStore<T> implements Store<T> {
    private final Path path;
    private final Class<T> type;
    private final T empty;
    private final ObjectMapper mapper;

    public JsonStore(Path path, Class<T> type, T empty) {
        this.path = path;
        this.type = type;
        this.empty = empty;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized T load() {
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                save(empty);
                return empty;
            }
            return mapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load json from " + path, e);
        }
    }

    public synchronized void save(T value) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save json to " + path, e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
