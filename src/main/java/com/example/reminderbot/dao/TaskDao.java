package com.example.reminderbot.dao;

import com.example.reminderbot.model.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TaskDao {
    public TaskDao() {}

    public List<TaskDefinition> loadAll(Connection conn) throws SQLException {
        List<TaskDefinition> tasks = new ArrayList<>();
        String sql = "SELECT id, title, kind, frequency_unit, frequency_interval, recommended_slots, note FROM tasks WHERE active = true ORDER BY created_at";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String unitStr = rs.getString("frequency_unit");
                Integer interval = (Integer) rs.getObject("frequency_interval");
                ScheduleRule schedule = unitStr != null && interval != null
                        ? new ScheduleRule(FrequencyUnit.valueOf(unitStr), interval)
                        : null;
                tasks.add(new TaskDefinition(
                        rs.getString("id"),
                        rs.getString("title"),
                        TaskKind.valueOf(rs.getString("kind")),
                        schedule,
                        rs.getInt("recommended_slots"),
                        rs.getString("note")
                ));
            }
        }
        return tasks;
    }

    public TaskDefinition findById(Connection conn, String id) throws SQLException {
        String sql = "SELECT id, title, kind, frequency_unit, frequency_interval, recommended_slots, note FROM tasks WHERE id = ? AND active = true";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String unitStr = rs.getString("frequency_unit");
                    Integer interval = (Integer) rs.getObject("frequency_interval");
                    ScheduleRule schedule = unitStr != null && interval != null
                            ? new ScheduleRule(FrequencyUnit.valueOf(unitStr), interval)
                            : null;
                    return new TaskDefinition(
                            rs.getString("id"),
                            rs.getString("title"),
                            TaskKind.valueOf(rs.getString("kind")),
                            schedule,
                            rs.getInt("recommended_slots"),
                            rs.getString("note")
                    );
                }
            }
        }
        return null;
    }

    public Map<String, TaskDefinition> findAllByIds(Connection conn, Set<String> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<String, TaskDefinition> tasks = new HashMap<>();
        String sql = """
            SELECT id, title, kind, frequency_unit, frequency_interval, recommended_slots, note
            FROM tasks
            WHERE active = true AND id = ANY (?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String unitStr = rs.getString("frequency_unit");
                    Integer interval = (Integer) rs.getObject("frequency_interval");
                    ScheduleRule schedule = unitStr != null && interval != null
                            ? new ScheduleRule(FrequencyUnit.valueOf(unitStr), interval)
                            : null;
                    TaskDefinition task = new TaskDefinition(
                            rs.getString("id"),
                            rs.getString("title"),
                            TaskKind.valueOf(rs.getString("kind")),
                            schedule,
                            rs.getInt("recommended_slots"),
                            rs.getString("note")
                    );
                    tasks.put(task.id(), task);
                }
            }
        }
        return tasks;
    }

    public void upsert(Connection conn, TaskDefinition task) throws SQLException {
        String sql = """
            INSERT INTO tasks (id, title, kind, frequency_unit, frequency_interval, recommended_slots, note, active, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, true, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                kind = EXCLUDED.kind,
                frequency_unit = EXCLUDED.frequency_unit,
                frequency_interval = EXCLUDED.frequency_interval,
                recommended_slots = EXCLUDED.recommended_slots,
                note = EXCLUDED.note,
                updated_at = CURRENT_TIMESTAMP
            """;
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, task.id());
                ps.setString(2, task.title());
                ps.setString(3, task.kind().name());
                ps.setString(4, task.schedule() != null ? task.schedule().unit().name() : null);
                ps.setObject(5, task.schedule() != null ? task.schedule().interval() : null);
                ps.setInt(6, task.recommendedSlots());
                ps.setString(7, task.note());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void delete(Connection conn, String taskId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tasks SET active = false, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setString(1, taskId);
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
