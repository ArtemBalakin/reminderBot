package com.example.reminderbot.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.sql.*;

public class PostgresStore<T> implements Store<T> {
    private final String url;
    private final String username;
    private final String password;
    private final String schema;
    private final String stateKey;
    private final Class<T> type;
    private final T empty;
    private final ObjectMapper mapper;

    public PostgresStore(String url, String username, String password, String schema, String stateKey, Class<T> type, T empty) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.schema = schema == null || schema.isBlank() ? "public" : schema;
        this.stateKey = stateKey;
        this.type = type;
        this.empty = empty;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        init();
    }

    @Override
    public synchronized T load() {
        try (Connection c = connect()) {
            upsertEmptyIfMissing(c);
            try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM " + qualifiedTable() + " WHERE state_key = ?")) {
                ps.setString(1, stateKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return empty;
                    String json = rs.getString(1);
                    return mapper.readValue(json, type);
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to load state from PostgreSQL for key=" + stateKey, e);
        }
    }

    @Override
    public synchronized void save(T value) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + qualifiedTable() + " (state_key, payload, updated_at) VALUES (?, CAST(? AS jsonb), now()) " +
                             "ON CONFLICT (state_key) DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()")) {
            ps.setString(1, stateKey);
            ps.setString(2, mapper.writeValueAsString(value));
            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to save state to PostgreSQL for key=" + stateKey, e);
        }
    }

    @Override
    public ObjectMapper mapper() {
        return mapper;
    }

    private void init() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdent(schema));
            st.execute("CREATE TABLE IF NOT EXISTS " + qualifiedTable() + " (" +
                    "state_key text PRIMARY KEY, " +
                    "payload jsonb NOT NULL, " +
                    "updated_at timestamptz NOT NULL DEFAULT now()" +
                    ")");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize PostgreSQL store", e);
        }
    }

    private void upsertEmptyIfMissing(Connection c) throws SQLException, IOException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + qualifiedTable() + " (state_key, payload, updated_at) VALUES (?, CAST(? AS jsonb), now()) ON CONFLICT (state_key) DO NOTHING")) {
            ps.setString(1, stateKey);
            ps.setString(2, mapper.writeValueAsString(empty));
            ps.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private String qualifiedTable() {
        return quoteIdent(schema) + "." + quoteIdent("bot_store");
    }

    private String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
