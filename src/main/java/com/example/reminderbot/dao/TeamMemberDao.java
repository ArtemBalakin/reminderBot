package com.example.reminderbot.dao;

import com.example.reminderbot.model.TeamMember;
import com.example.reminderbot.model.TeamRole;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class TeamMemberDao {
    public TeamMemberDao() {}

    public TeamMember findByChatId(Connection conn, long chatId) throws SQLException {
        String sql = "SELECT team_id, chat_id, role FROM team_members WHERE chat_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    public List<TeamMember> findByTeamId(Connection conn, String teamId) throws SQLException {
        String sql = """
            SELECT team_id, chat_id, role FROM team_members
            WHERE team_id = ?
            ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, joined_at
            """;
        List<TeamMember> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readRow(rs));
            }
        }
        return result;
    }

    public long countByTeamId(Connection conn, String teamId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM team_members WHERE team_id = ?")) {
            ps.setString(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private TeamMember readRow(ResultSet rs) throws SQLException {
        return new TeamMember(
                rs.getString("team_id"),
                rs.getLong("chat_id"),
                TeamRole.valueOf(rs.getString("role"))
        );
    }

    public void add(Connection conn, String teamId, long chatId, TeamRole role) throws SQLException {
        String sql = "INSERT INTO team_members (team_id, chat_id, role) VALUES (?, ?, ?)";
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, teamId);
                ps.setLong(2, chatId);
                ps.setString(3, role.name());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void updateRole(Connection conn, long chatId, TeamRole role) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE team_members SET role = ? WHERE chat_id = ?")) {
                ps.setString(1, role.name());
                ps.setLong(2, chatId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void remove(Connection conn, long chatId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM team_members WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void removeAllByTeamId(Connection conn, String teamId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM team_members WHERE team_id = ?")) {
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
