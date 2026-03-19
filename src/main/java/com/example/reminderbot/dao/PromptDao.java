package com.example.reminderbot.dao;

import com.example.reminderbot.model.ActivePrompt;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PromptDao {
    private final DataSource dataSource;

    public PromptDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<ActivePrompt> loadAll(Connection conn) throws SQLException {
        List<ActivePrompt> prompts = new ArrayList<>();
        String sql = """
            SELECT id, subscription_id, task_id, chat_id, scheduled_for, next_ping_at, state, 
                   message_id, alert_broadcast_count, end_of_day_alert_sent, stage_started_at, current_stage_alert_sent
            FROM prompts
            ORDER BY scheduled_for
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp nextPing = rs.getTimestamp("next_ping_at");
                Timestamp stageStarted = rs.getTimestamp("stage_started_at");
                prompts.add(new ActivePrompt(
                        rs.getString("id"),
                        rs.getString("subscription_id"),
                        rs.getString("task_id"),
                        rs.getLong("chat_id"),
                        rs.getTimestamp("scheduled_for").toInstant(),
                        nextPing != null ? nextPing.toInstant() : null,
                        rs.getString("state"),
                        (Integer) rs.getObject("message_id"),
                        rs.getInt("alert_broadcast_count"),
                        rs.getBoolean("end_of_day_alert_sent"),
                        stageStarted != null ? stageStarted.toInstant() : null,
                        rs.getBoolean("current_stage_alert_sent")
                ));
            }
        }
        return prompts;
    }

    public void upsert(Connection conn, ActivePrompt prompt) throws SQLException {
        String sql = """
            INSERT INTO prompts (id, subscription_id, task_id, chat_id, scheduled_for, next_ping_at, state, 
                                 message_id, alert_broadcast_count, end_of_day_alert_sent, stage_started_at, 
                                 current_stage_alert_sent, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
                subscription_id = EXCLUDED.subscription_id,
                task_id = EXCLUDED.task_id,
                chat_id = EXCLUDED.chat_id,
                scheduled_for = EXCLUDED.scheduled_for,
                next_ping_at = EXCLUDED.next_ping_at,
                state = EXCLUDED.state,
                message_id = EXCLUDED.message_id,
                alert_broadcast_count = EXCLUDED.alert_broadcast_count,
                end_of_day_alert_sent = EXCLUDED.end_of_day_alert_sent,
                stage_started_at = EXCLUDED.stage_started_at,
                current_stage_alert_sent = EXCLUDED.current_stage_alert_sent,
                updated_at = CURRENT_TIMESTAMP
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prompt.id());
            ps.setString(2, prompt.subscriptionId());
            ps.setString(3, prompt.taskId());
            ps.setLong(4, prompt.chatId());
            ps.setTimestamp(5, Timestamp.from(prompt.scheduledFor()));
            ps.setTimestamp(6, prompt.nextPingAt() != null ? Timestamp.from(prompt.nextPingAt()) : null);
            ps.setString(7, prompt.state());
            ps.setObject(8, prompt.messageId());
            ps.setInt(9, prompt.alertBroadcastCount());
            ps.setBoolean(10, prompt.endOfDayAlertSent());
            ps.setTimestamp(11, prompt.stageStartedAt() != null ? Timestamp.from(prompt.stageStartedAt()) : null);
            ps.setBoolean(12, prompt.currentStageAlertSent());
            ps.executeUpdate();
        }
    }

    public void delete(Connection conn, String promptId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM prompts WHERE id = ?")) {
            ps.setString(1, promptId);
            ps.executeUpdate();
        }
    }
}
