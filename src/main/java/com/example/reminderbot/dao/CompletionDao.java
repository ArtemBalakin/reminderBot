package com.example.reminderbot.dao;

import com.example.reminderbot.model.CompletionRecord;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CompletionDao {
    private final DataSource dataSource;

    public CompletionDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<CompletionRecord> loadAll(Connection conn) throws SQLException {
        List<CompletionRecord> completions = new ArrayList<>();
        String sql = """
            SELECT prompt_id, subscription_id, task_id, chat_id, scheduled_for, completed_at
            FROM completion_records
            WHERE completed_at > CURRENT_TIMESTAMP - INTERVAL '14 days'
            ORDER BY completed_at DESC
            LIMIT 500
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                completions.add(new CompletionRecord(
                        rs.getString("prompt_id"),
                        rs.getString("subscription_id"),
                        rs.getString("task_id"),
                        rs.getLong("chat_id"),
                        rs.getTimestamp("scheduled_for").toInstant(),
                        rs.getTimestamp("completed_at").toInstant()
                ));
            }
        }
        return completions;
    }

    public void insert(Connection conn, CompletionRecord record) throws SQLException {
        String sql = """
            INSERT INTO completion_records (prompt_id, subscription_id, task_id, chat_id, scheduled_for, completed_at, outcome)
            VALUES (?, ?, ?, ?, ?, ?, 'DONE')
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.promptId());
            ps.setString(2, record.subscriptionId());
            ps.setString(3, record.taskId());
            ps.setLong(4, record.chatId());
            ps.setTimestamp(5, Timestamp.from(record.scheduledFor()));
            ps.setTimestamp(6, Timestamp.from(record.completedAt()));
            ps.executeUpdate();
        }
    }

    public void cleanupOld(Connection conn, Instant cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM completion_records WHERE completed_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.executeUpdate();
        }
    }
}
