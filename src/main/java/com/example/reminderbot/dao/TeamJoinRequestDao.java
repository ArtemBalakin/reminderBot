package com.example.reminderbot.dao;

import com.example.reminderbot.model.TeamJoinRequest;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class TeamJoinRequestDao {
    public TeamJoinRequestDao() {}

    public TeamJoinRequest findByChatId(Connection conn, long chatId) throws SQLException {
        String sql = "SELECT id, team_id, chat_id FROM team_join_requests WHERE chat_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    public TeamJoinRequest findById(Connection conn, String id) throws SQLException {
        String sql = "SELECT id, team_id, chat_id FROM team_join_requests WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    public List<TeamJoinRequest> findByTeamId(Connection conn, String teamId) throws SQLException {
        String sql = "SELECT id, team_id, chat_id FROM team_join_requests WHERE team_id = ? ORDER BY requested_at";
        List<TeamJoinRequest> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readRow(rs));
            }
        }
        return result;
    }

    private TeamJoinRequest readRow(ResultSet rs) throws SQLException {
        return new TeamJoinRequest(rs.getString("id"), rs.getString("team_id"), rs.getLong("chat_id"));
    }

    public void create(Connection conn, TeamJoinRequest request) throws SQLException {
        String sql = "INSERT INTO team_join_requests (id, team_id, chat_id) VALUES (?, ?, ?)";
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, request.id());
                ps.setString(2, request.teamId());
                ps.setLong(3, request.chatId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void delete(Connection conn, String id) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM team_join_requests WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void deleteByTeamId(Connection conn, String teamId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM team_join_requests WHERE team_id = ?")) {
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
