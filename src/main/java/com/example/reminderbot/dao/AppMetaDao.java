package com.example.reminderbot.dao;

import java.sql.*;

public class AppMetaDao {
    public AppMetaDao() {}

    public long getLastUpdateId(Connection conn) throws SQLException {
        String sql = "SELECT meta_value FROM app_meta WHERE meta_key = 'last_update_id'";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Long.parseLong(rs.getString("meta_value"));
            }
            return 0L;
        }
    }

    public void setLastUpdateId(Connection conn, long updateId) throws SQLException {
        String sql = """
            INSERT INTO app_meta (meta_key, meta_value, updated_at)
            VALUES ('last_update_id', ?, CURRENT_TIMESTAMP)
            ON CONFLICT (meta_key) DO UPDATE SET
                meta_value = EXCLUDED.meta_value,
                updated_at = CURRENT_TIMESTAMP
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(updateId));
            ps.executeUpdate();
        }
    }
}
