package com.example.reminderbot.dao;

import com.example.reminderbot.model.UserProfile;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class UserDao {
    public UserDao() {}

    public Map<Long, UserProfile> loadAll(Connection conn) throws SQLException {
        Map<Long, UserProfile> users = new HashMap<>();
        String sql = "SELECT chat_id, username, first_name, zone_id, alerts_enabled, reping_minutes FROM users";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long chatId = rs.getLong("chat_id");
                users.put(chatId, new UserProfile(
                        chatId,
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getString("zone_id"),
                        rs.getBoolean("alerts_enabled"),
                        rs.getInt("reping_minutes"),
                        null
                ));
            }
        }
        return users;
    }

    public UserProfile findById(Connection conn, long chatId) throws SQLException {
        String sql = "SELECT chat_id, username, first_name, zone_id, alerts_enabled, reping_minutes FROM users WHERE chat_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new UserProfile(
                            rs.getLong("chat_id"),
                            rs.getString("username"),
                            rs.getString("first_name"),
                            rs.getString("zone_id"),
                            rs.getBoolean("alerts_enabled"),
                            rs.getInt("reping_minutes"),
                            null
                    );
                }
            }
        }
        return null;
    }

    public void upsert(Connection conn, UserProfile user) throws SQLException {
        String sql = """
            INSERT INTO users (chat_id, username, first_name, zone_id, alerts_enabled, reping_minutes, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (chat_id) DO UPDATE SET
                username = EXCLUDED.username,
                first_name = EXCLUDED.first_name,
                zone_id = EXCLUDED.zone_id,
                alerts_enabled = EXCLUDED.alerts_enabled,
                reping_minutes = EXCLUDED.reping_minutes,
                updated_at = CURRENT_TIMESTAMP
            """;
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, user.chatId());
                ps.setString(2, user.username());
                ps.setString(3, user.firstName());
                ps.setString(4, user.zoneId());
                ps.setBoolean(5, user.alertsEnabled());
                ps.setInt(6, user.repingMinutes() == null ? 5 : user.repingMinutes());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void delete(Connection conn, long chatId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }
}
