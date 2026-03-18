package com.example.reminderbot.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

public interface Store<T> {
    T load();
    void save(T value);
    ObjectMapper mapper();
}
