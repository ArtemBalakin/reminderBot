package com.example.reminderbot.dao;

import com.example.reminderbot.model.UserSession;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class SessionDao {
    private final ObjectMapper mapper;

    public SessionDao(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<Long, UserSession> loadAll(Connection conn) throws SQLException {
        Map<Long, UserSession> sessions = new HashMap<>();
        String sql = "SELECT chat_id, session_type, session_data, helper_message_id FROM sessions";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long chatId = rs.getLong("chat_id");
                String sessionType = rs.getString("session_type");
                String sessionDataJson = rs.getString("session_data");
                Integer helperMsgId = (Integer) rs.getObject("helper_message_id");
                
                Map<String, String> data = new HashMap<>();
                if (sessionDataJson != null && !sessionDataJson.isBlank()) {
                    try {
                        data = mapper.readValue(sessionDataJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                    } catch (Exception e) {
                        // ignore malformed data
                    }
                }
                
                sessions.put(chatId, new UserSession(
                        com.example.reminderbot.model.SessionType.valueOf(sessionType),
                        data,
                        helperMsgId
                ));
            }
        }
        return sessions;
    }

    public UserSession findById(Connection conn, long chatId) throws SQLException {
        String sql = "SELECT chat_id, session_type, session_data, helper_message_id FROM sessions WHERE chat_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sessionType = rs.getString("session_type");
                    String sessionDataJson = rs.getString("session_data");
                    Integer helperMsgId = (Integer) rs.getObject("helper_message_id");
                    
                    Map<String, String> data = new HashMap<>();
                    if (sessionDataJson != null && !sessionDataJson.isBlank()) {
                        try {
                            data = mapper.readValue(sessionDataJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                        } catch (Exception e) {}
                    }
                    
                    return new UserSession(
                            com.example.reminderbot.model.SessionType.valueOf(sessionType),
                            data,
                            helperMsgId
                    );
                }
            }
        }
        return null;
    }

    public void upsert(Connection conn, long chatId, UserSession session) throws SQLException {
        String sql = """
            INSERT INTO sessions (chat_id, session_type, session_data, helper_message_id, updated_at)
            VALUES (?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (chat_id) DO UPDATE SET
                session_type = EXCLUDED.session_type,
                session_data = EXCLUDED.session_data,
                helper_message_id = EXCLUDED.helper_message_id,
                updated_at = CURRENT_TIMESTAMP
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, session.type().name());
            ps.setString(3, mapper.writeValueAsString(session.data()));
            ps.setObject(4, session.helperMessageId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SQLException("Failed to upsert session", e);
        }
    }

    public void delete(Connection conn, long chatId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE chat_id = ?")) {
            ps.setLong(1, chatId);
            ps.executeUpdate();
        }
    }
}
