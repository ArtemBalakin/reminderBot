package com.example.reminderbot.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.sql.*;
import java.util.Properties;

public class PostgresStore<T> implements Store<T> {
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String schema;
    private final String storeKey;
    private final Class<T> type;
    private final T empty;
    private final ObjectMapper mapper;

    public PostgresStore(String jdbcUrl, String user, String password, String schema, String storeKey, Class<T> type, T empty) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.schema = schema == null || schema.isBlank() ? "public" : schema;
        this.storeKey = storeKey;
        this.type = type;
        this.empty = empty;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        init();
    }

    private Connection connect() throws SQLException {
        Properties props = new Properties();
        if (user != null) props.setProperty("user", user);
        if (password != null) props.setProperty("password", password);
        return DriverManager.getConnection(jdbcUrl, props);
    }

    private void init() {
        try (Connection con = connect(); Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute("CREATE TABLE IF NOT EXISTS " + schema + ".bot_store (store_key TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO " + schema + ".bot_store(store_key, payload) VALUES (?, ?) ON CONFLICT (store_key) DO NOTHING")) {
                ps.setString(1, storeKey);
                ps.setString(2, mapper.writeValueAsString(empty));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize postgres store", e);
        }
    }

    @Override
    public synchronized T load() {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement("SELECT payload FROM " + schema + ".bot_store WHERE store_key = ?")) {
            ps.setString(1, storeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.readValue(rs.getString(1), type);
                }
            }
            save(empty);
            return empty;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load data from postgres store", e);
        }
    }

    @Override
    public synchronized void save(T value) {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(
                "INSERT INTO " + schema + ".bot_store(store_key, payload, updated_at) VALUES (?, ?, now()) " +
                        "ON CONFLICT (store_key) DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()")) {
            ps.setString(1, storeKey);
            ps.setString(2, mapper.writeValueAsString(value));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save data to postgres store", e);
        }
    }

    @Override
    public ObjectMapper mapper() {
        return mapper;
    }
}
