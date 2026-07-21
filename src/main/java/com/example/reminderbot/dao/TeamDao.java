package com.example.reminderbot.dao;

import com.example.reminderbot.model.Team;

import java.sql.*;

public class TeamDao {
    public TeamDao() {}

    public Team findById(Connection conn, String id) throws SQLException {
        String sql = "SELECT id, name, owner_chat_id, active FROM teams WHERE id = ? AND active = true";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    public Team findByName(Connection conn, String name) throws SQLException {
        String sql = "SELECT id, name, owner_chat_id, active FROM teams WHERE LOWER(name) = LOWER(?) AND active = true";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    private Team readRow(ResultSet rs) throws SQLException {
        return new Team(
                rs.getString("id"),
                rs.getString("name"),
                rs.getLong("owner_chat_id"),
                rs.getBoolean("active")
        );
    }

    public void insert(Connection conn, Team team) throws SQLException {
        String sql = """
            INSERT INTO teams (id, name, owner_chat_id, active, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, team.id());
                ps.setString(2, team.name());
                ps.setLong(3, team.ownerChatId());
                ps.setBoolean(4, team.active());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void rename(Connection conn, String teamId, String newName) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE teams SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setString(1, newName);
                ps.setString(2, teamId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void delete(Connection conn, String teamId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE teams SET active = false, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setString(1, teamId);
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
