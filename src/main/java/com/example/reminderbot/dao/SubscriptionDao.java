package com.example.reminderbot.dao;

import com.example.reminderbot.model.Subscription;

import java.sql.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SubscriptionDao {
    public SubscriptionDao() {}

    public List<Subscription> loadAll(Connection conn) throws SQLException {
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month,
                   s.next_run_at, s.active, s.one_time_done
            FROM subscriptions s
            WHERE s.active = true
            ORDER BY s.created_at
            """;
        return loadSubscriptions(conn, sql, null);
    }

    public List<Subscription> findAllByChatId(Connection conn, long chatId) throws SQLException {
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month,
                   s.next_run_at, s.active, s.one_time_done
            FROM subscriptions s
            WHERE s.chat_id = ?
            ORDER BY s.created_at
            """;
        return loadSubscriptions(conn, sql, ps -> ps.setLong(1, chatId));
    }

    public Subscription findById(Connection conn, String id) throws SQLException {
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month,
                   s.next_run_at, s.active, s.one_time_done
            FROM subscriptions s
            WHERE s.id = ?
            """;
        List<Subscription> subscriptions = loadSubscriptions(conn, sql, ps -> ps.setString(1, id));
        return subscriptions.isEmpty() ? null : subscriptions.get(0);
    }

    public Subscription findActiveByChatAndTask(Connection conn, long chatId, String taskId) throws SQLException {
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month,
                   s.next_run_at, s.active, s.one_time_done
            FROM subscriptions s
            WHERE s.chat_id = ? AND s.task_id = ? AND s.active = true
            ORDER BY s.created_at
            LIMIT 1
            """;
        List<Subscription> subscriptions = loadSubscriptions(conn, sql, ps -> {
            ps.setLong(1, chatId);
            ps.setString(2, taskId);
        });
        return subscriptions.isEmpty() ? null : subscriptions.get(0);
    }

    public long countActiveByTaskId(Connection conn, String taskId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM subscriptions WHERE task_id = ? AND active = true";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long countActive(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM subscriptions WHERE active = true";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public List<Subscription> findDue(Connection conn, Instant cutoff) throws SQLException {
        String sql = """
            SELECT s.id, s.task_id, s.chat_id, s.day_of_week, s.day_of_month,
                   s.next_run_at, s.active, s.one_time_done
            FROM subscriptions s
            LEFT JOIN prompts p ON s.id = p.subscription_id
            WHERE s.active = true 
              AND s.one_time_done = false
              AND s.next_run_at <= ? 
              AND p.id IS NULL
            ORDER BY s.next_run_at
            """;
        return loadSubscriptions(conn, sql, ps -> ps.setTimestamp(1, Timestamp.from(cutoff)));
    }

    private List<Subscription> loadSubscriptions(Connection conn, String sql, StatementBinder binder) throws SQLException {
        List<BaseSubscriptionRow> baseRows = new ArrayList<>();
        List<String> subscriptionIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (binder != null) binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    Timestamp nextRunTs = rs.getTimestamp("next_run_at");
                    subscriptionIds.add(id);
                    baseRows.add(new BaseSubscriptionRow(
                            id,
                            rs.getString("task_id"),
                            rs.getLong("chat_id"),
                            rs.getString("day_of_week"),
                            (Integer) rs.getObject("day_of_month"),
                            nextRunTs != null ? nextRunTs.toInstant() : null,
                            rs.getBoolean("active"),
                            rs.getBoolean("one_time_done")
                    ));
                }
            }
        }

        if (baseRows.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> dailyTimesBySubscription = loadDailyTimes(conn, subscriptionIds);
        Map<String, List<String>> daysOfWeekBySubscription = loadDaysOfWeek(conn, subscriptionIds);
        Map<String, List<Integer>> daysOfMonthBySubscription = loadDaysOfMonth(conn, subscriptionIds);

        List<Subscription> hydrated = new ArrayList<>(baseRows.size());
        for (BaseSubscriptionRow row : baseRows) {
            hydrated.add(new Subscription(
                    row.id(),
                    row.taskId(),
                    row.chatId(),
                    dailyTimesBySubscription.getOrDefault(row.id(), Collections.emptyList()),
                    row.dayOfWeek(),
                    row.dayOfMonth(),
                    row.nextRunAt(),
                    row.active(),
                    row.oneTimeDone(),
                    daysOfWeekBySubscription.get(row.id()),
                    daysOfMonthBySubscription.get(row.id())
            ));
        }
        return hydrated;
    }

    private Map<String, List<String>> loadDailyTimes(Connection conn, List<String> subscriptionIds) throws SQLException {
        Map<String, List<String>> timesBySubscription = new HashMap<>();
        String sql = "SELECT subscription_id, slot_time FROM subscription_daily_times WHERE subscription_id = ANY (?) ORDER BY subscription_id, slot_time";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", subscriptionIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String subscriptionId = rs.getString("subscription_id");
                    timesBySubscription
                            .computeIfAbsent(subscriptionId, ignored -> new ArrayList<>())
                            .add(rs.getTime("slot_time").toLocalTime().toString());
                }
            }
        }
        return timesBySubscription;
    }

    private Map<String, List<String>> loadDaysOfWeek(Connection conn, List<String> subscriptionIds) throws SQLException {
        Map<String, List<String>> daysBySubscription = new HashMap<>();
        String sql = "SELECT subscription_id, day_of_week FROM subscription_days_of_week WHERE subscription_id = ANY (?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", subscriptionIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String subscriptionId = rs.getString("subscription_id");
                    daysBySubscription
                            .computeIfAbsent(subscriptionId, ignored -> new ArrayList<>())
                            .add(rs.getString("day_of_week"));
                }
            }
        }
        return daysBySubscription;
    }

    private Map<String, List<Integer>> loadDaysOfMonth(Connection conn, List<String> subscriptionIds) throws SQLException {
        Map<String, List<Integer>> daysBySubscription = new HashMap<>();
        String sql = "SELECT subscription_id, day_of_month FROM subscription_days_of_month WHERE subscription_id = ANY (?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", subscriptionIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String subscriptionId = rs.getString("subscription_id");
                    daysBySubscription
                            .computeIfAbsent(subscriptionId, ignored -> new ArrayList<>())
                            .add(rs.getInt("day_of_month"));
                }
            }
        }
        return daysBySubscription;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private record BaseSubscriptionRow(
            String id,
            String taskId,
            long chatId,
            String dayOfWeek,
            Integer dayOfMonth,
            Instant nextRunAt,
            boolean active,
            boolean oneTimeDone
    ) {}

    public void upsert(Connection conn, Subscription sub) throws SQLException {
        String sql = """
            INSERT INTO subscriptions (id, task_id, chat_id, day_of_week, day_of_month, next_run_at, active, one_time_done, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                chat_id = EXCLUDED.chat_id,
                day_of_week = EXCLUDED.day_of_week,
                day_of_month = EXCLUDED.day_of_month,
                next_run_at = EXCLUDED.next_run_at,
                active = EXCLUDED.active,
                one_time_done = EXCLUDED.one_time_done,
                updated_at = CURRENT_TIMESTAMP
            """;
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sub.id());
                ps.setString(2, sub.taskId());
                ps.setLong(3, sub.chatId());
                ps.setString(4, sub.dayOfWeek());
                ps.setObject(5, sub.dayOfMonth());
                ps.setTimestamp(6, sub.nextRunAt() != null ? Timestamp.from(sub.nextRunAt()) : null);
                ps.setBoolean(7, sub.active());
                ps.setBoolean(8, sub.oneTimeDone());
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
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        }
    }

    public void delete(Connection conn, String subscriptionId) throws SQLException {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subscriptions WHERE id = ?")) {
                ps.setString(1, subscriptionId);
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
