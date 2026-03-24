package com.example.reminderbot.dao;

import com.example.reminderbot.model.Subscription;

import java.sql.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class SubscriptionDao {
    public SubscriptionDao() {}

    public List<Subscription> loadAll(Connection conn) throws SQLException {
        List<Subscription> subscriptions = new ArrayList<>();
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month, s.zone_id, 
                   s.next_run_at, s.active, s.one_time_done, s.days_of_week, s.days_of_month
            FROM subscriptions s
            WHERE s.active = true
            ORDER BY s.created_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                subscriptions.add(readRow(conn, rs));
            }
        }
        return subscriptions;
    }

    private Subscription readRow(Connection conn, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        List<String> dailyTimes = loadDailyTimes(conn, id);
        List<String> dws = loadDaysOfWeek(conn, id);
        List<Integer> dms = loadDaysOfMonth(conn, id);
        Timestamp nextRunTs = rs.getTimestamp("next_run_at");

        return new Subscription(
                id,
                rs.getString("task_id"),
                rs.getLong("chat_id"),
                dailyTimes,
                rs.getString("day_of_week"),
                (Integer) rs.getObject("day_of_month"),
                rs.getString("zone_id"),
                nextRunTs != null ? nextRunTs.toInstant() : null,
                rs.getBoolean("active"),
                rs.getBoolean("one_time_done"),
                dws,
                dms
        );
    }

    public List<Subscription> findAllByChatId(Connection conn, long chatId) throws SQLException {
        List<Subscription> subscriptions = new ArrayList<>();
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month, s.zone_id, 
                   s.next_run_at, s.active, s.one_time_done, s.days_of_week, s.days_of_month
            FROM subscriptions s
            WHERE s.chat_id = ?
            ORDER BY s.created_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) subscriptions.add(readRow(conn, rs));
            }
        }
        return subscriptions;
    }

    public Subscription findById(Connection conn, String id) throws SQLException {
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month, s.zone_id, 
                   s.next_run_at, s.active, s.one_time_done, s.days_of_week, s.days_of_month
            FROM subscriptions s
            WHERE s.id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readRow(conn, rs);
            }
        }
        return null;
    }

    public List<Subscription> findDue(Connection conn, Instant cutoff) throws SQLException {
        List<Subscription> subscriptions = new ArrayList<>();
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month, s.zone_id, 
                   s.next_run_at, s.active, s.one_time_done, s.days_of_week, s.days_of_month
            FROM subscriptions s
            LEFT JOIN prompts p ON s.id = p.subscription_id
            WHERE s.active = true 
              AND s.one_time_done = false
              AND s.next_run_at <= ? 
              AND p.id IS NULL
            ORDER BY s.next_run_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) subscriptions.add(readRow(conn, rs));
            }
        }
        return subscriptions;
    }

    private List<String> loadDailyTimes(Connection conn, String subscriptionId) throws SQLException {
        List<String> times = new ArrayList<>();
        String sql = "SELECT slot_time FROM subscription_daily_times WHERE subscription_id = ? ORDER BY slot_time";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    times.add(rs.getTime("slot_time").toLocalTime().toString());
                }
            }
        }
        return times;
    }

    private List<String> loadDaysOfWeek(Connection conn, String subscriptionId) throws SQLException {
        List<String> days = new ArrayList<>();
        String sql = "SELECT day_of_week FROM subscription_days_of_week WHERE subscription_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) days.add(rs.getString("day_of_week"));
            }
        }
        return days.isEmpty() ? null : days;
    }

    private List<Integer> loadDaysOfMonth(Connection conn, String subscriptionId) throws SQLException {
        List<Integer> days = new ArrayList<>();
        String sql = "SELECT day_of_month FROM subscription_days_of_month WHERE subscription_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) days.add(rs.getInt("day_of_month"));
            }
        }
        return days.isEmpty() ? null : days;
    }

    public void upsert(Connection conn, Subscription sub) throws SQLException {
        String sql = """
            INSERT INTO subscriptions (id, task_id, chat_id, day_of_week, day_of_month, zone_id, next_run_at, active, one_time_done, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                chat_id = EXCLUDED.chat_id,
                day_of_week = EXCLUDED.day_of_week,
                day_of_month = EXCLUDED.day_of_month,
                zone_id = EXCLUDED.zone_id,
                next_run_at = EXCLUDED.next_run_at,
                active = EXCLUDED.active,
                one_time_done = EXCLUDED.one_time_done,
                updated_at = CURRENT_TIMESTAMP
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sub.id());
            ps.setString(2, sub.taskId());
            ps.setLong(3, sub.chatId());
            ps.setString(4, sub.dayOfWeek());
            ps.setObject(5, sub.dayOfMonth());
            ps.setString(6, sub.zoneId());
            ps.setTimestamp(7, sub.nextRunAt() != null ? Timestamp.from(sub.nextRunAt()) : null);
            ps.setBoolean(8, sub.active());
            ps.setBoolean(9, sub.oneTimeDone());
            ps.executeUpdate();
        }

        try (PreparedStatement del = conn.prepareStatement("DELETE FROM subscription_daily_times WHERE subscription_id = ?")) {
            del.setString(1, sub.id());
            del.executeUpdate();
        }
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM subscription_days_of_week WHERE subscription_id = ?")) {
            del.setString(1, sub.id());
            del.executeUpdate();
        }
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM subscription_days_of_month WHERE subscription_id = ?")) {
            del.setString(1, sub.id());
            del.executeUpdate();
        }
        if (sub.dailyTimes() != null && !sub.dailyTimes().isEmpty()) {
            String insertTimes = "INSERT INTO subscription_daily_times (subscription_id, slot_time) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertTimes)) {
                for (String time : sub.dailyTimes()) {
                    ps.setString(1, sub.id());
                    ps.setTime(2, Time.valueOf(LocalTime.parse(time)));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        if (sub.daysOfWeek() != null && !sub.daysOfWeek().isEmpty()) {
            String insertDow = "INSERT INTO subscription_days_of_week (subscription_id, day_of_week) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertDow)) {
                for (String day : sub.daysOfWeek()) {
                    ps.setString(1, sub.id());
                    ps.setString(2, day);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        if (sub.daysOfMonth() != null && !sub.daysOfMonth().isEmpty()) {
            String insertDom = "INSERT INTO subscription_days_of_month (subscription_id, day_of_month) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertDom)) {
                for (Integer day : sub.daysOfMonth()) {
                    ps.setString(1, sub.id());
                    ps.setInt(2, day);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    public void delete(Connection conn, String subscriptionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subscriptions WHERE id = ?")) {
            ps.setString(1, subscriptionId);
            ps.executeUpdate();
        }
    }
}
