package com.example.reminderbot.service;

import com.example.reminderbot.model.*;
import com.example.reminderbot.storage.DatabaseStore;
import com.example.reminderbot.telegram.TelegramClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

public class BotService {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Duration GOING_DOING_CHECK_DELAY = Duration.ofMinutes(30);
    private static final Duration IGNORE_ALERT_DELAY = Duration.ofHours(3);
    private static final LocalTime TOMORROW_REMINDER_TIME = LocalTime.of(19, 0);
    private static final Duration TOMORROW_REMINDER_WINDOW = Duration.ofMinutes(5);
    private static final int DEFAULT_REPING_MINUTES = 5;
    private static final String STATE_START_WAITING = "START_WAITING";
    private static final String STATE_SNOOZED = "SNOOZED";
    private static final String STATE_GOING_DOING_DELAY = "GOING_DOING_DELAY";
    private static final String STATE_CHECK_WAITING = "CHECK_WAITING";

    private final TelegramClient telegram;
    private final DatabaseStore db;
    private final ZoneId defaultZone;
    private final String appBaseUrl;

    public BotService(TelegramClient telegram,
            DatabaseStore db,
            ZoneId defaultZone,
            String appBaseUrl) {
        this.telegram = telegram;
        this.db = db;
        this.defaultZone = defaultZone;
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        
        // Always try to set the menu button to the MiniApp to make it static
        this.telegram.setChatMenuButton("Открыть приложение", this.appBaseUrl + "/app");
    }

    public long getLastUpdateId() {
        try (java.sql.Connection conn = db.getConnection()) {
            return db.appMeta().getLastUpdateId(conn);
        } catch (java.sql.SQLException e) {
            System.err.println("SQL Error: " + e.getMessage());
            return 0;
        }
    }

    public void setLastUpdateId(long updateId) {
        try (java.sql.Connection conn = db.getConnection()) {
            db.appMeta().setLastUpdateId(conn, updateId);
        } catch (java.sql.SQLException e) {
            System.err.println("SQL Error: " + e.getMessage());
        }
    }

    // ── MiniApp API methods ────────────────────────────────────────────

    public Map<String, Object> apiGetTasksPage(int page) {
        try (java.sql.Connection conn = db.getConnection()) {
            List<TaskDefinition> tasks = db.tasks().loadAll(conn);
            int pageSize = 6;
            int totalPages = Math.max(1, (tasks.size() + pageSize - 1) / pageSize);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int from = safePage * pageSize;
            int to = Math.min(tasks.size(), from + pageSize);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = from; i < to; i++) {
                TaskDefinition t = tasks.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("number", i + 1);
                m.put("id", t.id());
                m.put("title", t.title());
                m.put("kind", t.kind().name());
                m.put("frequency", frequencyText(t));
                items.add(m);
            }
            return Map.of("tasks", items, "page", safePage, "totalPages", totalPages, "total", tasks.size());
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public Map<String, Object> apiGetTaskCard(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) return Map.of("error", "Не нашёл дело: " + ref);
            long subscribers = db.subscriptions().loadAll(conn).stream()
                    .filter(s -> s.taskId().equals(task.id()) && s.active()).count();
            Subscription own = findUserSubscription(conn, chatId, task.id());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", task.id());
            result.put("number", taskNumber(conn, task.id()));
            result.put("title", task.title());
            result.put("kind", task.kind().name());
            result.put("kindHuman", humanTaskKind(task.kind()));
            result.put("frequency", frequencyText(task));
            result.put("subscribers", subscribers);
            result.put("note", task.note());
            result.put("isManual", task.kind() == TaskKind.MANUAL);
            result.put("recommendedSlots", task.recommendedSlots());
            if (task.schedule() != null) {
                result.put("scheduleUnit", task.schedule().unit().name());
                result.put("scheduleInterval", task.schedule().interval());
            }
            if (own != null) {
                Map<String, Object> sub = new LinkedHashMap<>();
                sub.put("id", own.id());
                sub.put("schedule", humanSchedule(own, task));
                if (own.nextRunAt() != null) {
                    sub.put("nextRunAt", ZonedDateTime.ofInstant(own.nextRunAt(),
                            ZoneId.of(own.zoneId())).format(DATE_TIME_FMT));
                }
                if (own.dailyTimes() != null) sub.put("dailyTimes", own.dailyTimes());
                if (own.daysOfWeek() != null) sub.put("daysOfWeek", own.daysOfWeek());
                else if (own.dayOfWeek() != null) sub.put("dayOfWeek", own.dayOfWeek());
                if (own.daysOfMonth() != null) sub.put("daysOfMonth", own.daysOfMonth());
                else if (own.dayOfMonth() != null) sub.put("dayOfMonth", own.dayOfMonth());
                result.put("mySubscription", sub);
            }
            return result;
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public List<Map<String, Object>> apiGetSubscriptions(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            List<Map<String, Object>> result = new ArrayList<>();
            List<TaskDefinition> allTasks = db.tasks().loadAll(conn);
            for (Subscription sub : db.subscriptions().findAllByChatId(conn, chatId)) {
                if (!sub.active()) continue;
                TaskDefinition task = findTask(conn, sub.taskId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subscriptionId", sub.id());
                m.put("taskId", sub.taskId());
                m.put("taskTitle", task != null ? task.title() : sub.taskId());
                m.put("schedule", task != null ? humanSchedule(sub, task) : "");
                // Add task number for miniapp navigation
                int taskNumber = 1;
                for (int i = 0; i < allTasks.size(); i++) {
                    if (allTasks.get(i).id().equals(sub.taskId())) {
                        taskNumber = i + 1;
                        break;
                    }
                }
                m.put("taskNumber", taskNumber);
                if (sub.nextRunAt() != null) {
                    m.put("nextRunAt", ZonedDateTime.ofInstant(sub.nextRunAt(),
                            ZoneId.of(sub.zoneId())).format(DATE_TIME_FMT));
                }
                result.add(m);
            }
            return result;
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    public Map<String, Object> apiGetSettings(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile profile = getProfile(conn, chatId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatId", chatId);
            m.put("username", profile.username());
            m.put("firstName", profile.firstName());
            m.put("zoneId", profile.zoneId());
            m.put("alertsEnabled", profile.alertsEnabled());
            m.put("repingMinutes", normalizeRepingMinutes(profile.repingMinutes()));
            return m;
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public void apiUpdateSettings(long chatId, String zoneId, Boolean alertsEnabled,
            Integer repingMinutes) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile old = getProfile(conn, chatId);
            String newZone = old.zoneId();
            if (zoneId != null && !zoneId.isBlank()) {
                ZoneId.of(zoneId); // validate
                newZone = zoneId;
            }
            boolean newAlerts = alertsEnabled != null ? alertsEnabled : old.alertsEnabled();
            int newReping = repingMinutes != null && repingMinutes >= 1 && repingMinutes <= 180
                    ? repingMinutes : normalizeRepingMinutes(old.repingMinutes());
            db.users().upsert(conn, new UserProfile(old.chatId(), old.username(), old.firstName(),
                    newZone, newAlerts, newReping, old.lastTomorrowReminderForDate()));
        } catch (java.sql.SQLException e) {
            System.err.println("API db error: " + e.getMessage());
        }
    }

    public Map<String, Object> apiSubscribe(long chatId, String taskRef, String mode,
            List<String> times, String dateStr, String timeStr, List<String> daysOfWeek, List<Integer> daysOfMonth) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, taskRef);
            if (task == null) return Map.of("error", "Дело не найдено");
            if (task.kind() == TaskKind.MANUAL) return Map.of("error", "Ручное дело без расписания");
            UserProfile profile = getProfile(conn, chatId);
            Subscription updated;
            if ("daily".equals(mode)) {
                List<String> parsed = times.stream()
                        .map(t -> parseTime(t).format(TIME_FMT)).sorted().distinct().toList();
                if (parsed.isEmpty()) return Map.of("error", "Нужно хотя бы одно время");
                updated = buildDailySubscription(conn, profile, task, new ArrayList<>(parsed));
            } else if ("weekly".equals(mode)) {
                List<String> parsed = times.stream()
                        .map(t -> parseTime(t).format(TIME_FMT)).sorted().distinct().toList();
                if (parsed.isEmpty()) return Map.of("error", "Нужно хотя бы одно время");
                if (daysOfWeek == null || daysOfWeek.isEmpty()) return Map.of("error", "Нужно выбрать дни недели");
                if (parsed.size() * daysOfWeek.size() < (task.recommendedSlots() != null ? task.recommendedSlots() : 1)) {
                    return Map.of("error", "Нужно выбрать больше слотов");
                }
                updated = buildWeeklySubscription(conn, profile, task, new ArrayList<>(parsed), daysOfWeek);
            } else if ("monthly".equals(mode)) {
                List<String> parsed = times.stream()
                        .map(t -> parseTime(t).format(TIME_FMT)).sorted().distinct().toList();
                if (parsed.isEmpty()) return Map.of("error", "Нужно хотя бы одно время");
                if (daysOfMonth == null || daysOfMonth.isEmpty()) return Map.of("error", "Нужно выбрать числа месяца");
                if (parsed.size() * daysOfMonth.size() < (task.recommendedSlots() != null ? task.recommendedSlots() : 1)) {
                    return Map.of("error", "Нужно выбрать больше слотов");
                }
                updated = buildMonthlySubscription(conn, profile, task, new ArrayList<>(parsed), daysOfMonth);
            } else {
                LocalDate date = LocalDate.parse(dateStr);
                LocalTime time = parseTime(timeStr);
                updated = buildDatedSubscription(conn, profile, task, date, time);
            }
            db.subscriptions().upsert(conn, updated);
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("taskTitle", task.title());
            result.put("schedule", humanSchedule(updated, task));
            if (updated.nextRunAt() != null) {
                result.put("nextRunAt", ZonedDateTime.ofInstant(updated.nextRunAt(),
                        ZoneId.of(updated.zoneId())).format(DATE_TIME_FMT));
            }
            return result;
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public Map<String, Object> apiUnsubscribe(long chatId, String taskRef) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, taskRef);
            if (task == null) return Map.of("error", "Дело не найдено");
            Subscription sub = findUserSubscription(conn, chatId, task.id());
            if (sub != null) {
                db.subscriptions().delete(conn, sub.id());
                db.prompts().deleteBySubscriptionId(conn, sub.id());
            }
            return Map.of("ok", sub != null, "taskTitle", task.title());
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public Map<String, Object> apiCreateTask(String title, String kindCode, String unit,
            int interval, int slots, String note) {
        try (java.sql.Connection conn = db.getConnection()) {
            String id = slugify(title);
            if (findTask(conn, id) != null) id = id + "-" + shortId().substring(0, 4);
            TaskKind kind;
            ScheduleRule schedule = null;
            int finalSlots = Math.max(1, slots);
            switch (kindCode) {
                case "DAY" -> { kind = TaskKind.RECURRING; schedule = new ScheduleRule(FrequencyUnit.DAY, Math.max(1, interval)); }
                case "WEEK" -> { kind = TaskKind.RECURRING; schedule = new ScheduleRule(FrequencyUnit.WEEK, Math.max(1, interval)); }
                case "MONTH" -> { kind = TaskKind.RECURRING; schedule = new ScheduleRule(FrequencyUnit.MONTH, Math.max(1, interval)); }
                case "THIS_WEEK" -> kind = TaskKind.ONE_TIME_THIS_WEEK;
                case "NEXT_WEEK" -> kind = TaskKind.ONE_TIME_NEXT_WEEK;
                case "MANUAL" -> { kind = TaskKind.MANUAL; finalSlots = 0; }
                default -> { return Map.of("error", "Неизвестный тип: " + kindCode); }
            }
            String cleanNote = note != null && !note.isBlank() && !note.equals("-") ? note.trim() : null;
            TaskDefinition task = new TaskDefinition(id, title.trim(), kind, schedule, finalSlots, cleanNote);
            db.tasks().upsert(conn, task);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("id", task.id());
            result.put("title", task.title());
            result.put("frequency", frequencyText(task));
            return result;
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public Map<String, Object> apiUpdateTask(String taskId, String title, String kindCode,
            int interval, int slots, String note) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition existing = findTask(conn, taskId);
            if (existing == null) return Map.of("error", "Дело не найдено");
            
            TaskKind kind;
            ScheduleRule schedule = null;
            int finalSlots = Math.max(1, slots);
            switch (kindCode) {
                case "DAY" -> { kind = TaskKind.RECURRING; schedule = new ScheduleRule(FrequencyUnit.DAY, Math.max(1, interval)); }
                case "WEEK" -> { kind = TaskKind.RECURRING; schedule = new ScheduleRule(FrequencyUnit.WEEK, Math.max(1, interval)); }
                case "MONTH" -> { kind = TaskKind.RECURRING; schedule = new ScheduleRule(FrequencyUnit.MONTH, Math.max(1, interval)); }
                case "THIS_WEEK" -> kind = TaskKind.ONE_TIME_THIS_WEEK;
                case "NEXT_WEEK" -> kind = TaskKind.ONE_TIME_NEXT_WEEK;
                case "MANUAL" -> { kind = TaskKind.MANUAL; finalSlots = 0; }
                default -> { return Map.of("error", "Неизвестный тип: " + kindCode); }
            }
            
            String cleanTitle = title != null && !title.isBlank() ? title.trim() : existing.title();
            String cleanNote = note != null && !note.isBlank() && !note.equals("-") ? note.trim() : null;
            if (note != null && note.equals("-")) cleanNote = null; // explicit delete
            else if (note == null) cleanNote = existing.note(); // keep existing
            
            TaskDefinition updated = new TaskDefinition(existing.id(), cleanTitle, kind, schedule, finalSlots, cleanNote);
            db.tasks().upsert(conn, updated);
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("id", updated.id());
            result.put("title", updated.title());
            result.put("frequency", frequencyText(updated));
            return result;
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public Map<String, Object> apiEditTask(String taskId, String property, String value) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = findTask(conn, taskId);
            if (task == null) return Map.of("error", "Дело не найдено");
            TaskDefinition updated = switch (property) {
                case "title" -> {
                    if (value == null || value.isBlank()) yield null;
                    yield new TaskDefinition(task.id(), value.trim(), task.kind(), task.schedule(),
                            task.recommendedSlots(), task.note());
                }
                case "interval" -> {
                    if (task.schedule() == null) yield null;
                    int newInterval;
                    try { newInterval = Integer.parseInt(value.trim()); if (newInterval <= 0) throw new NumberFormatException(); }
                    catch (NumberFormatException e) { yield null; }
                    yield new TaskDefinition(task.id(), task.title(), task.kind(),
                            new ScheduleRule(task.schedule().unit(), newInterval), task.recommendedSlots(), task.note());
                }
                case "note" -> {
                    String newNote = value != null && !value.trim().equals("-") && !value.isBlank() ? value.trim() : null;
                    yield new TaskDefinition(task.id(), task.title(), task.kind(), task.schedule(),
                            task.recommendedSlots(), newNote);
                }
                default -> null;
            };
            if (updated == null) return Map.of("error", "Не удалось обновить свойство " + property);
            db.tasks().upsert(conn, updated);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("id", updated.id());
            result.put("title", updated.title());
            result.put("frequency", frequencyText(updated));
            return result;
        } catch (java.sql.SQLException e) {
            return Map.of("error", "Ошибка БД");
        }
    }

    public Map<String, Object> apiGetTodayBoard(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            String sql = """
                WITH today_prompts AS (
                    SELECT DISTINCT ON (p.subscription_id)
                        p.subscription_id, p.state, p.next_ping_at
                    FROM prompts p
                    JOIN subscriptions s ON s.id = p.subscription_id
                    JOIN users u ON u.chat_id = s.chat_id
                    WHERE p.scheduled_for BETWEEN NOW() - INTERVAL '2 days' AND NOW() + INTERVAL '1 day'
                      AND (p.scheduled_for AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date
                    ORDER BY p.subscription_id, p.scheduled_for DESC
                ),
                today_completions AS (
                    SELECT cr.subscription_id, COUNT(*) as cnt
                    FROM completion_records cr
                    JOIN subscriptions s ON s.id = cr.subscription_id
                    JOIN users u ON u.chat_id = s.chat_id
                    WHERE cr.scheduled_for BETWEEN NOW() - INTERVAL '2 days' AND NOW() + INTERVAL '1 day'
                      AND (cr.scheduled_for AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date
                    GROUP BY cr.subscription_id
                )
                SELECT
                    u.chat_id, u.username, u.first_name, u.zone_id,
                    t.title AS task_title,
                    s.next_run_at,
                    tp.state AS prompt_state,
                    COALESCE(tc.cnt, 0) AS completion_count,
                    CASE WHEN s.next_run_at IS NOT NULL
                         AND s.next_run_at BETWEEN NOW() - INTERVAL '1 day' AND NOW() + INTERVAL '1 day'
                         AND (s.next_run_at AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date
                         THEN true ELSE false END AS has_today_future
                FROM subscriptions s
                JOIN tasks t ON t.id = s.task_id AND t.active = true
                JOIN users u ON u.chat_id = s.chat_id
                LEFT JOIN today_prompts tp ON tp.subscription_id = s.id
                LEFT JOIN today_completions tc ON tc.subscription_id = s.id
                WHERE s.active = true AND t.kind != 'MANUAL'
                  AND (tp.subscription_id IS NOT NULL
                       OR tc.subscription_id IS NOT NULL
                       OR (s.next_run_at IS NOT NULL
                           AND s.next_run_at BETWEEN NOW() - INTERVAL '1 day' AND NOW() + INTERVAL '1 day'
                           AND (s.next_run_at AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date))
                ORDER BY COALESCE(u.username, u.first_name, u.chat_id::text), t.title
                """;
            Map<Long, Map<String, Object>> sectionMap = new LinkedHashMap<>();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long uid = rs.getLong("chat_id");
                    String username = rs.getString("username");
                    String firstName = rs.getString("first_name");
                    String zoneId = rs.getString("zone_id");
                    String taskTitle = rs.getString("task_title");
                    String promptState = rs.getString("prompt_state");
                    int completionCount = rs.getInt("completion_count");
                    boolean hasTodayFuture = rs.getBoolean("has_today_future");
                    java.sql.Timestamp nextRunAt = rs.getTimestamp("next_run_at");

                    ZoneId zone;
                    try { zone = ZoneId.of(zoneId); } catch (Exception e) { zone = defaultZone; }

                    String status;
                    boolean done;
                    if (promptState != null) {
                        status = switch (promptState) {
                            case "SNOOZED" -> "отложено";
                            case "GOING_DOING_DELAY" -> "пошёл делать";
                            case "CHECK_WAITING" -> "ждёт подтверждения";
                            default -> "ждёт ответа";
                        };
                        done = false;
                    } else if (completionCount > 0) {
                        status = "сделано";
                        done = true;
                    } else {
                        status = "запланировано на " + DATE_TIME_FMT.format(
                                ZonedDateTime.ofInstant(nextRunAt.toInstant(), zone));
                        done = false;
                    }

                    String displayName = (username != null && !username.isBlank())
                            ? "@" + username : firstName + " (" + uid + ")";

                    @SuppressWarnings("unchecked")
                    Map<String, Object> section = sectionMap.computeIfAbsent(uid, k -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("user", displayName);
                        s.put("tasks", new ArrayList<Map<String, Object>>());
                        return s;
                    });
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("taskTitle", taskTitle);
                    row.put("status", status);
                    row.put("done", done);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tasks = (List<Map<String, Object>>) section.get("tasks");
                    tasks.add(row);
                }
            }
            return Map.of("sections", new ArrayList<>(sectionMap.values()));
        } catch (java.sql.SQLException e) {
            System.err.println("apiGetTodayBoard SQL error: " + e.getMessage());
            return Map.of("sections", List.of());
        }
    }

    private int calcSubSlots(Subscription sub) {
        int times = sub.dailyTimes() == null || sub.dailyTimes().isEmpty() ? 1 : sub.dailyTimes().size();
        int days = 1;
        if (sub.daysOfWeek() != null && !sub.daysOfWeek().isEmpty()) days = sub.daysOfWeek().size();
        else if (sub.daysOfMonth() != null && !sub.daysOfMonth().isEmpty()) days = sub.daysOfMonth().size();
        return times * days;
    }

    public Map<String, Object> apiGetBoard() {
        try (java.sql.Connection conn = db.getConnection()) {
            List<Map<String, Object>> free = new ArrayList<>();
            List<Map<String, Object>> taken = new ArrayList<>();
            Map<String, List<Subscription>> taskSubs = new HashMap<>();
            for (Subscription sub : db.subscriptions().loadAll(conn)) {
                if (sub.active()) taskSubs.computeIfAbsent(sub.taskId(), k -> new ArrayList<>()).add(sub);
            }
            for (TaskDefinition task : db.tasks().loadAll(conn)) {
                List<Subscription> subs = taskSubs.getOrDefault(task.id(), List.of());
                int takenSlots = subs.stream().mapToInt(this::calcSubSlots).sum();
                int slots = task.recommendedSlots();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", task.id());
                item.put("title", task.title());
                item.put("count", takenSlots);
                item.put("slots", slots);
                item.put("full", takenSlots >= slots);
                if (!subs.isEmpty()) {
                    item.put("users", subs.stream().map(s -> {
                        try { return displayUser(conn, s.chatId()); } catch (Exception e) { return String.valueOf(s.chatId()); }
                    }).toList());
                    taken.add(item);
                } else {
                    free.add(item);
                }
            }
            return Map.of("free", free, "taken", taken);
        } catch (java.sql.SQLException e) {
            return Map.of("free", List.of(), "taken", List.of());
        }
    }

    public Map<String, Object> apiGetStats() {
        try (java.sql.Connection conn = db.getConnection()) {
            Map<String, Integer> taskCounts = new HashMap<>();
            List<Subscription> subscriptions = db.subscriptions().loadAll(conn);
            for (Subscription sub : subscriptions) {
                if (sub.active()) taskCounts.merge(sub.taskId(), calcSubSlots(sub), Integer::sum);
            }
            List<Map<String, Object>> items = taskCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .map(e -> {
                        TaskDefinition task;
                        try { task = findTask(conn, e.getKey()); } catch (Exception ex) { task = null; }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("taskTitle", task != null ? task.title() : e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    }).toList();
            long totalActive = subscriptions.stream().filter(Subscription::active).count();
            return Map.of("items", items, "totalActive", totalActive);
        } catch (java.sql.SQLException e) {
            return Map.of("items", List.of(), "totalActive", 0);
        }
    }

    public Map<String, Object> apiGetCalendar(int year, int month, String zoneIdStr) {
        try (java.sql.Connection conn = db.getConnection()) {
            ZoneId zone = zoneIdStr != null && !zoneIdStr.isBlank() ? ZoneId.of(zoneIdStr) : defaultZone;
            LocalDate start = LocalDate.of(year, month, 1);
            int length = start.lengthOfMonth();

            Map<String, List<Map<String, Object>>> daysMap = new LinkedHashMap<>();
            for (int i = 1; i <= length; i++) {
                daysMap.put(start.withDayOfMonth(i).toString(), new ArrayList<>());
            }

            for (Subscription sub : db.subscriptions().loadAll(conn)) {
                if (!sub.active()) continue;
                TaskDefinition task = findTask(conn, sub.taskId());
                if (task == null || task.kind() == TaskKind.MANUAL) continue;

                List<LocalDate> runDates = new ArrayList<>();
                if (task.kind() == TaskKind.ONE_TIME_THIS_WEEK || task.kind() == TaskKind.ONE_TIME_NEXT_WEEK) {
                    if (sub.nextRunAt() != null && !sub.oneTimeDone()) {
                        LocalDate runDate = ZonedDateTime.ofInstant(sub.nextRunAt(), zone).toLocalDate();
                        if (runDate.getYear() == year && runDate.getMonthValue() == month) {
                            runDates.add(runDate);
                        }
                    }
                } else if (task.kind() == TaskKind.RECURRING && sub.nextRunAt() != null) {
                    LocalDate anchorDate = ZonedDateTime.ofInstant(sub.nextRunAt(), zone).toLocalDate();
                    for (int i = 1; i <= length; i++) {
                        LocalDate date = start.withDayOfMonth(i);
                        boolean matches = false;
                        switch (task.schedule().unit()) {
                            case DAY -> {
                                long daysDiff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(anchorDate, date));
                                if (daysDiff % task.schedule().interval() == 0) matches = true;
                            }
                            case WEEK -> {
                                LocalDate anchorMonday = anchorDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                                LocalDate dateMonday = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                                long weeksDiff = Math.abs(java.time.temporal.ChronoUnit.WEEKS.between(anchorMonday, dateMonday));
                                if (weeksDiff % task.schedule().interval() == 0) {
                                    if (sub.daysOfWeek() != null && sub.daysOfWeek().contains(date.getDayOfWeek().name())) {
                                        matches = true;
                                    }
                                }
                            }
                            case MONTH -> {
                                LocalDate anchorStart = anchorDate.withDayOfMonth(1);
                                LocalDate dateStart = date.withDayOfMonth(1);
                                long monthsDiff = Math.abs(java.time.temporal.ChronoUnit.MONTHS.between(anchorStart, dateStart));
                                if (monthsDiff % task.schedule().interval() == 0) {
                                    if (sub.daysOfMonth() != null && sub.daysOfMonth().contains(date.getDayOfMonth())) {
                                        matches = true;
                                    }
                                }
                            }
                        }
                        if (matches) runDates.add(date);
                    }
                }

                if (!runDates.isEmpty()) {
                    String userName = displayUser(conn, sub.chatId());
                    Map<String, Object> taskInfo = Map.of(
                            "id", task.id(),
                            "title", task.title(),
                            "user", userName
                    );
                    for (LocalDate d : runDates) {
                        daysMap.get(d.toString()).add(taskInfo);
                    }
                }
            }
            return Map.of("days", daysMap);
        } catch (java.sql.SQLException e) {
            return Map.of("days", Map.of());
        }
    }

    public Map<String, Object> apiGetCalendarOverview(int year, int month, String zoneIdStr) {
        Map<String, Object> full = apiGetCalendar(year, month, zoneIdStr);
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> daysMap = (Map<String, List<Map<String, Object>>>) full.get("days");
        if (daysMap == null) daysMap = new HashMap<>();
        Map<String, Object> overviewDays = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : daysMap.entrySet()) {
            overviewDays.put(e.getKey(), Map.of("count", e.getValue().size()));
        }
        return Map.of("days", overviewDays);
    }

    public Map<String, Object> apiGetCalendarDayTasks(String dateStr, String zoneIdStr, int page, int size) {
        LocalDate date = LocalDate.parse(dateStr);
        Map<String, Object> full = apiGetCalendar(date.getYear(), date.getMonthValue(), zoneIdStr);
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> daysMap = (Map<String, List<Map<String, Object>>>) full.get("days");
        if (daysMap == null) daysMap = new HashMap<>();
        List<Map<String, Object>> tasks = daysMap.getOrDefault(date.toString(), List.of());
        
        int total = tasks.size();
        int safeSize = Math.max(1, size);
        int totalPages = Math.max(1, (total + safeSize - 1) / safeSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * safeSize;
        int to = Math.min(total, from + safeSize);
        List<Map<String, Object>> paginated = from < to ? tasks.subList(from, to) : List.of();
        
        return Map.of("tasks", paginated, "page", safePage, "totalPages", totalPages, "total", total);
    }

    public void handleUpdate(TelegramClient.Update update) {
        if (update.callbackQuery() != null) {
            handleCallback(update.callbackQuery());
            return;
        }
        if (update.message() != null && update.message().chat() != null) {
            handleMessage(update.message());
        }
    }

    public void processDueItems() {
        List<DueAction> actions = new ArrayList<>();
        List<AlertAction> alerts = new ArrayList<>();
        List<TomorrowReminderAction> tomorrowReminders = new ArrayList<>();
        
        try (java.sql.Connection conn = db.getConnection()) {
            Instant now = Instant.now();
            
            // 1. Process due subscriptions (new prompts)
            List<Subscription> dueSubs = db.subscriptions().findDue(conn, now);
            for (Subscription sub : dueSubs) {
                TaskDefinition task = findTask(conn, sub.taskId());
                if (task == null) continue;
                
                ActivePrompt prompt = new ActivePrompt(
                        shortId(), sub.id(), sub.taskId(), sub.chatId(), sub.nextRunAt(),
                        now.plus(userRepingDelay(conn, sub.chatId())),
                        STATE_START_WAITING, null, 0, false, now, false);
                
                db.prompts().upsert(conn, prompt);
                try {
                    Subscription advanced = advanceSubscription(sub, task, sub.nextRunAt(), now);
                    db.subscriptions().upsert(conn, advanced);
                    System.out.println("[processDueItems] Advanced sub " + sub.id() + " (" + task.title() + ") nextRunAt: " + sub.nextRunAt() + " -> " + advanced.nextRunAt());
                } catch (Exception e) {
                    System.err.println("[processDueItems] Failed to advance sub " + sub.id() + ": " + e.getMessage());
                    e.printStackTrace();
                    // Fallback: move nextRunAt to tomorrow to prevent infinite re-triggering
                    Instant fallback = now.plus(Duration.ofDays(1));
                    Subscription fallbackSub = new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(),
                            sub.dayOfWeek(), sub.dayOfMonth(), sub.zoneId(), fallback, sub.active(), sub.oneTimeDone(),
                            sub.daysOfWeek(), sub.daysOfMonth());
                    db.subscriptions().upsert(conn, fallbackSub);
                    System.err.println("[processDueItems] Fallback nextRunAt for sub " + sub.id() + ": " + fallback);
                }
                actions.add(new DueAction(prompt, task, PromptReason.FIRST));
            }
            
            // 2. Process existing active prompts
            List<ActivePrompt> prompts = db.prompts().loadAll(conn);
            for (ActivePrompt prompt : prompts) {
                TaskDefinition task = findTask(conn, prompt.taskId());
                if (task == null) continue;

                ZoneId zone = ZoneId.of(userZone(conn, prompt.chatId()));
                LocalDate scheduledDate = ZonedDateTime.ofInstant(prompt.scheduledFor(), zone).toLocalDate();
                LocalDate today = ZonedDateTime.ofInstant(now, zone).toLocalDate();
                
                ActivePrompt updated = prompt;
                if (!prompt.endOfDayAlertSent() && today.isAfter(scheduledDate)) {
                    updated = new ActivePrompt(
                            updated.id(), updated.subscriptionId(), updated.taskId(), updated.chatId(),
                            updated.scheduledFor(), updated.nextPingAt(),
                            updated.state(), updated.messageId(), updated.alertBroadcastCount(), true,
                            updated.stageStartedAt(), updated.currentStageAlertSent());
                    db.prompts().upsert(conn, updated);
                    alerts.add(new AlertAction(updated, task, AlertReason.END_OF_DAY));
                }

                if (updated.nextPingAt() == null || updated.nextPingAt().isAfter(now))
                    continue;

                if (STATE_SNOOZED.equals(updated.state())) {
                    updated = new ActivePrompt(
                            updated.id(), updated.subscriptionId(), updated.taskId(), updated.chatId(),
                            updated.scheduledFor(), now.plus(userRepingDelay(conn, updated.chatId())),
                            STATE_START_WAITING, updated.messageId(), updated.alertBroadcastCount(),
                            updated.endOfDayAlertSent(), now, false);
                    db.prompts().upsert(conn, updated);
                    actions.add(new DueAction(updated, task, PromptReason.SNOOZE_FINISHED));
                    continue;
                }

                if (STATE_GOING_DOING_DELAY.equals(updated.state())) {
                    updated = new ActivePrompt(
                            updated.id(), updated.subscriptionId(), updated.taskId(), updated.chatId(),
                            updated.scheduledFor(), now.plus(userRepingDelay(conn, updated.chatId())),
                            STATE_CHECK_WAITING, updated.messageId(), updated.alertBroadcastCount(),
                            updated.endOfDayAlertSent(), now, false);
                    db.prompts().upsert(conn, updated);
                    actions.add(new DueAction(updated, task, PromptReason.CHECK_AFTER_WORK));
                    continue;
                }

                boolean isStartStage = STATE_START_WAITING.equals(updated.state());
                boolean isCheckStage = STATE_CHECK_WAITING.equals(updated.state());
                if (!isStartStage && !isCheckStage)
                    continue;

                Instant stageStartedAt = updated.stageStartedAt() == null ? now : updated.stageStartedAt();
                boolean shouldBroadcast = !updated.currentStageAlertSent()
                        && !Duration.between(stageStartedAt, now).minus(IGNORE_ALERT_DELAY).isNegative();
                
                updated = new ActivePrompt(
                        updated.id(), updated.subscriptionId(), updated.taskId(), updated.chatId(), updated.scheduledFor(),
                        now.plus(userRepingDelay(conn, updated.chatId())),
                        updated.state(), updated.messageId(), updated.alertBroadcastCount() + (shouldBroadcast ? 1 : 0),
                        updated.endOfDayAlertSent(), stageStartedAt, updated.currentStageAlertSent() || shouldBroadcast);
                
                db.prompts().upsert(conn, updated);
                actions.add(new DueAction(updated, task,
                        isStartStage ? PromptReason.REPEAT : PromptReason.CHECK_AFTER_WORK));
                
                if (shouldBroadcast) {
                    alerts.add(new AlertAction(updated, task,
                            isStartStage ? AlertReason.START_IGNORED : AlertReason.CHECK_IGNORED));
                }
            }
            
            collectTomorrowReminders(conn, now, tomorrowReminders);
        } catch (java.sql.SQLException e) {
            System.err.println("Database error during processDueItems: " + e.getMessage());
        }

        for (DueAction action : actions) {
            sendPrompt(action.prompt(), action.task(), action.reason());
        }
        for (AlertAction alert : alerts) {
            sendGlobalAlert(alert.prompt(), alert.task(), alert.reason());
        }
        for (TomorrowReminderAction reminder : tomorrowReminders) {
            telegram.sendMessage(reminder.chatId(), reminder.text());
        }
    }

    private void handleMessage(TelegramClient.Message message) {
        UserProfile profile = registerUser(message);
        long chatId = profile.chatId();

        if (message.webAppData() != null && message.webAppData().data() != null) {
            handleWebAppPayload(profile, message);
            return;
        }

        String text = message.text() == null ? "" : message.text().trim();
        UserSession session = null;
        try (java.sql.Connection conn = db.getConnection()) {
            session = getSession(conn, chatId);
        } catch (java.sql.SQLException e) {
            System.err.println("DB error " + e.getMessage());
        }

        if (session != null && message.document() != null) {
            if (session.type() == SessionType.IMPORT_CATALOG_FILE) {
                handleImportFile(profile, message.document());
                return;
            }
            if (session.type() == SessionType.IMPORT_DB_FILE) {
                handleImportDbFile(profile, message.document());
                return;
            }
        }
        if (session != null && session.type() == SessionType.CHANGE_TASK_SELECT && !text.isBlank()
                && !text.startsWith("/")) {
            handleChangeTaskSelectByText(chatId, text);
            return;
        }
        if (session != null && session.type() == SessionType.EDIT_TASK_SELECT && !text.isBlank()
                && !text.startsWith("/")) {
            handleEditTaskSelectByText(chatId, text);
            return;
        }
        if (session != null && session.type() == SessionType.EDIT_TASK_VALUE && !text.isBlank()
                && !text.startsWith("/")) {
            handleEditTaskValue(chatId, text);
            return;
        }
        if (text.equals("/cancel")) {
            try (java.sql.Connection conn = db.getConnection()) {
                db.sessions().delete(conn, chatId);
            } catch (java.sql.SQLException e) {}
            telegram.sendMessage(chatId, "Ок, отменил текущий сценарий.");
            return;
        }

        if (!text.isBlank() && !text.startsWith("/") && session != null) {
            switch (session.type()) {
                case NEW_TASK_TITLE -> {
                    handleNewTaskTitle(profile, text);
                    return;
                }
                case NEW_TASK_INTERVAL -> {
                    handleNewTaskInterval(profile, text);
                    return;
                }
                case NEW_TASK_NOTE -> {
                    handleNewTaskNote(profile, text);
                    return;
                }
                case EDIT_TASK_SELECT -> {
                    handleEditTaskSelectByText(chatId, text);
                    return;
                }
                case EDIT_TASK_VALUE -> {
                    handleEditTaskValue(chatId, text);
                    return;
                }
                default -> {
                }
            }
        }

        if (text.isBlank()) {
            return;
        }

        if (text.equals("/start")) {
            telegram.sendMessage(chatId, startText());
            telegram.sendMessage(chatId, "\u2699\uFE0F Быстрые действия:", startInlineKeyboard());
            return;
        }
        if (text.equals("/help")) {
            telegram.sendMessage(chatId, helpText());
            return;
        }
        if (text.equals("/tasks")) {
            sendTasksPage(chatId, 0);
            return;
        }
        if (text.startsWith("/task ")) {
            showTaskCard(chatId, text.substring(6).trim());
            return;
        }
        if (text.equals("/subs")) {
            try (java.sql.Connection conn = db.getConnection()) {
                telegram.sendMessage(chatId, subscriptionsText(conn, chatId));
            } catch (java.sql.SQLException e) { System.err.println("DB err " + e.getMessage()); }
            return;
        }
        if (text.equals("/today")) {
            telegram.sendMessage(chatId, todayBoardText());
            return;
        }
        if (text.startsWith("/who ")) {
            handleWho(chatId, text.substring(5).trim());
            return;
        }
        if (text.equals("/tz")) {
            telegram.sendMessage(chatId, timezoneText(chatId), timezoneKeyboard());
            return;
        }
        if (text.startsWith("/tzset ")) {
            handleTimezoneSet(chatId, text.substring(7).trim());
            return;
        }
        if (text.equals("/fastforward")) {
            handleFastForward(chatId);
            return;
        }
        if (text.equals("/alerts")) {
            telegram.sendMessage(chatId, alertsText(chatId), alertsKeyboard());
            return;
        }
        if (text.equalsIgnoreCase("/alerts on")) {
            setAlertsEnabled(chatId, true);
            telegram.sendMessage(chatId, "Ты подписан на общие алерты по пропущенным делам.");
            return;
        }
        if (text.equalsIgnoreCase("/alerts off")) {
            setAlertsEnabled(chatId, false);
            telegram.sendMessage(chatId, "Ты отписан от общих алертов.");
            return;
        }
        if (text.equals("/import")) {
            try (java.sql.Connection conn = db.getConnection()) {
                db.sessions().upsert(conn, chatId, new UserSession(SessionType.IMPORT_CATALOG_FILE, Map.of(), null));
            } catch (java.sql.SQLException e) { System.err.println("DB err " + e.getMessage()); }
            telegram.sendMessage(chatId,
                    "Пришли одним следующим сообщением JSON-файл каталога. Формат: объект с полем tasks.\n\n" +
                            "Например: {\"tasks\":[{\"title\":\"Мыть пол\",...}]}");
            return;
        }
        if (text.equals("/new")) {
            try (java.sql.Connection conn = db.getConnection()) {
                db.sessions().upsert(conn, chatId, new UserSession(SessionType.NEW_TASK_TITLE, Map.of(), null));
            } catch (java.sql.SQLException e) { System.err.println("DB err " + e.getMessage()); }
            telegram.sendMessage(chatId, "Как назвать новое дело? Просто пришли название одним сообщением.");
            return;
        }
        if (text.equals("/reload")) {
            telegram.sendMessage(chatId, "Состояние теперь хранится в базе данных и не требует ручной перезагрузки.");
            return;
        }
        if (text.startsWith("/reping ")) {
            handleRepingSet(chatId, text.substring(8).trim());
            return;
        }
        if (text.equals("/reping")) {
            telegram.sendMessage(chatId, repingText(chatId));
            return;
        }
        if (text.equals("/changetask")) {
            sendChangeTaskMenu(chatId);
            return;
        }
        if (text.startsWith("/changetask ")) {
            handleChangeTaskStart(chatId, text.substring(12).trim());
            return;
        }
        if (text.equals("/stats")) {
            telegram.sendMessage(chatId, subscriberStatsText());
            return;
        }
        if (text.equals("/board")) {
            telegram.sendMessage(chatId, taskBoardText());
            return;
        }
        if (text.equals("/edittask")) {
            sendEditTaskMenu(chatId);
            return;
        }
        if (text.startsWith("/edittask ")) {
            handleEditTaskStart(chatId, text.substring(10).trim());
            return;
        }

        telegram.sendMessage(chatId, "Не понял. Нажми /tasks, /new или /help.");
    }

    private void handleCallback(TelegramClient.CallbackQuery callback) {
        long chatId = callback.message() != null && callback.message().chat() != null
                ? callback.message().chat().id()
                : callback.from().id();
        String data = callback.data() == null ? "" : callback.data();
        try {
            if (data.startsWith("TASK_PAGE:")) {
                sendTasksPage(chatId, Integer.parseInt(data.substring(10)),
                        callback.message() != null ? callback.message().messageId() : null);
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("EDIT_TASK_PAGE:")) {
                sendEditTaskMenu(chatId, Integer.parseInt(data.substring(15)),
                        callback.message() != null ? callback.message().messageId() : null);
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("TASK_SHOW:")) {
                showTaskCard(chatId, data.substring(10));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("SUB_START:")) {
                startMiniAppSubscription(chatId, data.substring(10));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("WHO:")) {
                handleWho(chatId, data.substring(4));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("UNSUB:")) {
                handleUnsub(chatId, data.substring(6));
                telegram.answerCallbackQuery(callback.id(), "Удалено");
                return;
            }
            if (data.equals("TZ_MENU")) {
                telegram.sendMessage(chatId, timezoneText(chatId), timezoneKeyboard());
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("TZ:")) {
                handleTimezoneSet(chatId, data.substring(3));
                telegram.answerCallbackQuery(callback.id(), "Таймзона обновлена");
                return;
            }
            if (data.equals("ALERTS_ON")) {
                setAlertsEnabled(chatId, true);
                telegram.sendMessage(chatId, alertsText(chatId), alertsKeyboard());
                telegram.answerCallbackQuery(callback.id(), "Алерты включены");
                return;
            }
            if (data.equals("ALERTS_OFF")) {
                setAlertsEnabled(chatId, false);
                telegram.sendMessage(chatId, alertsText(chatId), alertsKeyboard());
                telegram.answerCallbackQuery(callback.id(), "Алерты выключены");
                return;
            }
            if (data.startsWith("NEW_KIND:")) {
                handleNewKind(chatId, data.substring(9));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("PROMPT_DONE:")) {
                handlePromptDone(callback.id(), data.substring(12), chatId,
                        callback.message() == null ? null : callback.message().messageId());
                return;
            }
            if (data.startsWith("PROMPT_MORE:")) {
                sendSnoozeHoursPicker(chatId, data.substring(12));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("PROMPT_SN:")) {
                String[] parts = data.split(":");
                handlePromptSnooze(callback.id(), parts[1], Integer.parseInt(parts[2]), chatId);
                return;
            }
            if (data.startsWith("PROMPT_GO:")) {
                handlePromptGoDoing(callback.id(), data.substring(10), chatId);
                return;
            }
            if (data.startsWith("CHANGE_TASK:")) {
                handleChangeTaskSelect(chatId, data.substring(12));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("CHANGE_TO:")) {
                String[] parts = data.split(":");
                try (java.sql.Connection conn = db.getConnection()) {
                    handleChangeTaskComplete(conn, callback.id(), chatId, parts[1], parts[2]);
                } catch (java.sql.SQLException e) { System.err.println("DB err " + e.getMessage()); }
                return;
            }
            if (data.startsWith("EDIT_TASK:")) {
                try (java.sql.Connection conn = db.getConnection()) {
                    handleEditTaskSelect(conn, chatId, data.substring(10));
                } catch (java.sql.SQLException e) { System.err.println("DB err " + e.getMessage()); }
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("EDIT_PROP:")) {
                String[] parts = data.split(":");
                handleEditTaskProperty(chatId, parts[1], parts[2]);
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
        } catch (Exception e) {
            telegram.answerCallbackQuery(callback.id(), "Ошибка: " + e.getMessage());
            return;
        }
        telegram.answerCallbackQuery(callback.id(), "Неизвестная кнопка");
    }

    public record CatalogImport(List<TaskDefinition> tasks) {}

    private void handleImportFile(UserProfile profile, TelegramClient.Document document) {
        try {
            String filePath = telegram.getFilePath(document.fileId());
            byte[] bytes = telegram.downloadFile(filePath);
            CatalogImport imported = db.mapper().readValue(new String(bytes, StandardCharsets.UTF_8), CatalogImport.class);
            try (java.sql.Connection conn = db.getConnection()) {
                for (TaskDefinition task : imported.tasks()) {
                    db.tasks().upsert(conn, task);
                }
                db.sessions().delete(conn, profile.chatId());
            }
            telegram.sendMessage(profile.chatId(), "Файл импортирован. Дел: " + imported.tasks().size());
        } catch (Exception e) {
            telegram.sendMessage(profile.chatId(), "Не смог импортировать файл: " + e.getMessage());
        }
    }

    private void handleWebAppPayload(UserProfile profile, TelegramClient.Message message) {
        long chatId = profile.chatId();
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, chatId);
            if (session == null || session.type() != SessionType.WAITING_WEBAPP_SUBSCRIPTION) {
                telegram.sendMessage(chatId,
                        "Планировщик открыт вне сценария настройки. Открой настройку заново через /tasks.");
                return;
            }
            JsonNode root = db.mapper().readTree(message.webAppData().data());
            String type = root.path("type").asText();
            if (!"subscription".equals(type)) {
                throw new IllegalArgumentException("Ожидался payload subscription");
            }
            String taskId = root.path("taskId").asText();
            TaskDefinition task = findTask(conn, taskId);
            if (task == null)
                throw new IllegalArgumentException("Дело не найдено");
            Subscription updated;
            if ("daily".equals(root.path("mode").asText())) {
                List<String> times = new ArrayList<>();
                for (JsonNode item : root.path("times"))
                    times.add(parseTime(item.asText()).format(TIME_FMT));
                if (times.isEmpty())
                    throw new IllegalArgumentException("Нужно хотя бы одно время");
                updated = buildDailySubscription(conn, profile, task, times);
            } else {
                LocalDate date = LocalDate.parse(root.path("date").asText());
                LocalTime time = parseTime(root.path("time").asText());
                updated = buildDatedSubscription(conn, profile, task, date, time);
            }
            db.subscriptions().upsert(conn, updated);
            db.sessions().delete(conn, chatId);
            
            if (session.helperMessageId() != null) {
                telegram.deleteMessage(chatId, session.helperMessageId());
            }
            if (message.messageId() != null) {
                telegram.deleteMessage(chatId, message.messageId());
            }
            telegram.sendMessage(chatId, subscriptionSummary(updated, task));
        } catch (Exception e) {
            telegram.sendMessage(chatId, "Не смог сохранить настройку из Mini App: " + e.getMessage());
        }
    }

    private void handleNewTaskTitle(UserProfile profile, String text) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, profile.chatId());
            if (session == null) return;
            Map<String, String> data = session.data();
            data.put("title", text.trim());
            db.sessions().upsert(conn, profile.chatId(),
                    new UserSession(SessionType.NEW_TASK_KIND, data, session.helperMessageId()));
        } catch (java.sql.SQLException e) {
            System.err.println("DB error " + e.getMessage());
        }
        telegram.sendMessage(profile.chatId(), "Какое правило у этого дела?", newTaskKindKeyboard());
    }

    private void handleNewKind(long chatId, String kindCode) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, chatId);
            if (session == null
                    || (session.type() != SessionType.NEW_TASK_KIND && session.type() != SessionType.NEW_TASK_INTERVAL)) {
                telegram.sendMessage(chatId, "Сначала нажми /new");
                return;
            }
            Map<String, String> data = session.data();
            switch (kindCode) {
                case "DAY" -> {
                    data.put("kind", TaskKind.RECURRING.name());
                    data.put("unit", FrequencyUnit.DAY.name());
                    db.sessions().upsert(conn, chatId,
                            new UserSession(SessionType.NEW_TASK_INTERVAL, data, session.helperMessageId()));
                    telegram.sendMessage(chatId, "Раз в сколько дней повторять? Пришли число, например 1 или 2.");
                }
                case "WEEK" -> {
                    data.put("kind", TaskKind.RECURRING.name());
                    data.put("unit", FrequencyUnit.WEEK.name());
                    db.sessions().upsert(conn, chatId,
                            new UserSession(SessionType.NEW_TASK_INTERVAL, data, session.helperMessageId()));
                    telegram.sendMessage(chatId, "Раз в сколько недель повторять? Пришли число, например 1 или 3.");
                }
                case "MONTH" -> {
                    data.put("kind", TaskKind.RECURRING.name());
                    data.put("unit", FrequencyUnit.MONTH.name());
                    db.sessions().upsert(conn, chatId,
                            new UserSession(SessionType.NEW_TASK_INTERVAL, data, session.helperMessageId()));
                    telegram.sendMessage(chatId, "Раз в сколько месяцев повторять? Пришли число, например 1 или 2.");
                }
                case "THIS_WEEK" -> saveNewTask(conn, chatId, data, TaskKind.ONE_TIME_THIS_WEEK, null, null, 1, 1);
                case "NEXT_WEEK" -> saveNewTask(conn, chatId, data, TaskKind.ONE_TIME_NEXT_WEEK, null, null, 1, 1);
                case "MANUAL" -> saveNewTask(conn, chatId, data, TaskKind.MANUAL, null, null, 0, 1);
                default -> telegram.sendMessage(chatId, "Неизвестный тип.");
            }
        } catch (java.sql.SQLException e) {
            System.err.println("DB error " + e.getMessage());
        }
    }

    private void handleNewTaskInterval(UserProfile profile, String text) {
        int interval;
        try {
            interval = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            telegram.sendMessage(profile.chatId(), "Нужно прислать целое число. Например 1 или 3.");
            return;
        }
        if (interval <= 0) {
            telegram.sendMessage(profile.chatId(), "Число должно быть больше 0.");
            return;
        }
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, profile.chatId());
            if (session == null) return;
            Map<String, String> data = session.data();
            data.put("interval", String.valueOf(interval));
            db.sessions().upsert(conn, profile.chatId(),
                    new UserSession(SessionType.NEW_TASK_NOTE, data, session.helperMessageId()));
        } catch (java.sql.SQLException e) {
            System.err.println("DB error " + e.getMessage());
        }
        telegram.sendMessage(profile.chatId(), "Нужна короткая заметка? Пришли текст или просто - если без заметки.");
    }

    private void handleNewTaskNote(UserProfile profile, String text) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, profile.chatId());
            if (session == null) return;
            Map<String, String> data = session.data();
            String note = text.trim().equals("-") ? null : text.trim();
            FrequencyUnit unit = FrequencyUnit.valueOf(data.get("unit"));
            int interval = Integer.parseInt(data.get("interval"));
            int slots = unit == FrequencyUnit.DAY ? 1 : 1;
            saveNewTask(conn, profile.chatId(), data, TaskKind.RECURRING, unit, note, slots, interval);
        } catch (java.sql.SQLException e) {
            System.err.println("DB error " + e.getMessage());
        }
    }

    private void saveNewTask(java.sql.Connection conn, long chatId, Map<String, String> data, TaskKind kind, FrequencyUnit unit, String note,
            int slots, int interval) throws java.sql.SQLException {
        String title = data.get("title");
        String id = slugify(title);
        if (findTask(conn, id) != null) {
            id = id + "-" + shortId().substring(0, 4);
        }
        TaskDefinition task = new TaskDefinition(
                id,
                title,
                kind,
                unit == null ? null : new ScheduleRule(unit, interval),
                slots,
                note);
        db.tasks().upsert(conn, task);
        db.sessions().delete(conn, chatId);
        telegram.sendMessage(chatId,
                "Добавил новое дело:\n\n" + title + "\nПравило: " + frequencyText(task) +
                        "\nДальше можешь открыть /tasks и настроить себе напоминание.");
    }

    private void startMiniAppSubscription(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) {
                telegram.sendMessage(chatId, "Не нашёл дело.");
                return;
            }
            if (task.kind() == TaskKind.MANUAL) {
                telegram.sendMessage(chatId, "Это ручное дело без расписания. Для него нет подписки по времени.");
                return;
            }
            UserProfile profile = getProfile(conn, chatId);
            Subscription existing = findUserSubscription(conn, chatId, task.id());
            String url = miniAppUrl(task, profile.zoneId(), existing);
            Map<String, Object> keyboard = TelegramClient.keyboard(List.of(
                    List.of(TelegramClient.webAppKeyboardButton("Открыть планировщик", url)),
                    List.of(Map.of("text", "/cancel"))), true, true);
            Integer msgId = telegram.sendMessage(chatId,
                    "Открой планировщик ниже. Внутри выбери дату и время, затем нажми «Сохранить».\n\n" +
                            "Для ежедневных дел можно добавить несколько времён сразу.",
                    keyboard,
                    false);
            db.sessions().upsert(conn, chatId, new UserSession(SessionType.WAITING_WEBAPP_SUBSCRIPTION,
                    Map.of("taskId", task.id()), msgId));
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка базы данных");
        }
    }

    private String miniAppUrl(TaskDefinition task, String zoneId, Subscription sub) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("taskId", task.id());
        params.put("title", task.title());
        params.put("kind", task.kind().name());
        params.put("zone", zoneId);
        params.put("slots", String.valueOf(task.recommendedSlots() != null ? task.recommendedSlots() : 1));
        if (task.schedule() != null) {
            params.put("unit", task.schedule().unit().name());
            params.put("interval", String.valueOf(task.schedule().interval()));
        }
        if (sub != null) {
            if (task.kind() == TaskKind.RECURRING && task.schedule() != null
                    && task.schedule().unit() == FrequencyUnit.DAY) {
                if (sub.dailyTimes() != null && !sub.dailyTimes().isEmpty()) {
                    params.put("times", String.join(",", sub.dailyTimes()));
                }
            } else if (sub.nextRunAt() != null) {
                ZonedDateTime zdt = ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(zoneId));
                params.put("date", zdt.toLocalDate().toString());
                params.put("time", zdt.toLocalTime().format(TIME_FMT));
            }
        }
        return appBaseUrl + "/miniapp?" + params.entrySet().stream()
                .map(e -> TelegramClient.encode(e.getKey()) + "=" + TelegramClient.encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private Subscription buildDailySubscription(java.sql.Connection conn, UserProfile profile, TaskDefinition task, List<String> times) throws java.sql.SQLException {
        ZoneId zone = ZoneId.of(profile.zoneId());
        Subscription existing = findUserSubscription(conn, profile.chatId(), task.id());
        List<String> normalized = times.stream().map(t -> parseTime(t).format(TIME_FMT)).sorted().distinct().toList();
        Instant next = computeNextDaily(normalized, task.schedule().interval(), zone, Instant.now());
        return new Subscription(
                existing == null ? shortId() : existing.id(),
                task.id(),
                profile.chatId(),
                normalized,
                null,
                null,
                profile.zoneId(),
                next,
                true,
                false,
                null,
                null);
    }

    private Subscription buildWeeklySubscription(java.sql.Connection conn, UserProfile profile, TaskDefinition task, List<String> times, List<String> daysOfWeek) throws java.sql.SQLException {
        ZoneId zone = ZoneId.of(profile.zoneId());
        Subscription existing = findUserSubscription(conn, profile.chatId(), task.id());
        Instant next = computeNextWeekly(times, daysOfWeek, task.schedule().interval(), zone, Instant.now());
        return new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                times, null, null, profile.zoneId(), next, true, false, daysOfWeek, null);
    }

    private Subscription buildMonthlySubscription(java.sql.Connection conn, UserProfile profile, TaskDefinition task, List<String> times, List<Integer> daysOfMonth) throws java.sql.SQLException {
        ZoneId zone = ZoneId.of(profile.zoneId());
        Subscription existing = findUserSubscription(conn, profile.chatId(), task.id());
        Instant next = computeNextMonthly(times, daysOfMonth, task.schedule().interval(), zone, Instant.now());
        return new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                times, null, null, profile.zoneId(), next, true, false, null, daysOfMonth);
    }

    private Subscription buildDatedSubscription(java.sql.Connection conn, UserProfile profile, TaskDefinition task, LocalDate date,
            LocalTime time) throws java.sql.SQLException {
        ZoneId zone = ZoneId.of(profile.zoneId());
        ZonedDateTime chosen = ZonedDateTime.of(date, time, zone);
        if (!chosen.toInstant().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Этот момент уже прошёл");
        }
        Subscription existing = findUserSubscription(conn, profile.chatId(), task.id());
        return switch (task.kind()) {
            case RECURRING -> {
                if (task.schedule().unit() == FrequencyUnit.WEEK) {
                    yield new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                            List.of(time.format(TIME_FMT)), null, null, profile.zoneId(),
                            chosen.toInstant(), true, false, List.of(date.getDayOfWeek().name()), null);
                } else if (task.schedule().unit() == FrequencyUnit.MONTH) {
                    yield new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                            List.of(time.format(TIME_FMT)), null, null, profile.zoneId(),
                            chosen.toInstant(), true, false, null, List.of(date.getDayOfMonth()));
                } else {
                    yield new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                            List.of(time.format(TIME_FMT)), null, null, profile.zoneId(), chosen.toInstant(), true,
                            false, null, null);
                }
            }
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK ->
                new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                        List.of(time.format(TIME_FMT)), null, null, profile.zoneId(), chosen.toInstant(), true, false, null, null);
            case MANUAL -> throw new IllegalArgumentException("Для ручного дела нельзя задать дату");
        };
    }

    private void sendTasksPage(long chatId, int page) {
        sendTasksPage(chatId, page, null);
    }

    private void sendTasksPage(long chatId, int page, Integer messageId) {
        try (java.sql.Connection conn = db.getConnection()) {
            List<TaskDefinition> tasks = db.tasks().loadAll(conn);
            if (tasks.isEmpty()) {
                telegram.sendMessage(chatId, "Каталог пуст. Пришли файл через /import или создай дело через /new.");
                return;
            }
            int pageSize = 6;
            int totalPages = Math.max(1, (tasks.size() + pageSize - 1) / pageSize);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int from = safePage * pageSize;
            int to = Math.min(tasks.size(), from + pageSize);
            StringBuilder sb = new StringBuilder("Дела — страница ").append(safePage + 1).append("/").append(totalPages)
                    .append("\n\n");
            for (int i = from; i < to; i++) {
                sb.append(i + 1).append(". ").append(tasks.get(i).title()).append("\n   ")
                        .append(frequencyText(tasks.get(i))).append("\n\n");
            }
            sb.append("Нажми на дело, чтобы открыть карточку и настроить себе напоминание.");

            List<List<Map<String, Object>>> rows = new ArrayList<>();
            for (int i = from; i < to; i++) {
                rows.add(List.of(
                        TelegramClient.button((i + 1) + ". " + trimTitle(tasks.get(i).title()), "TASK_SHOW:" + (i + 1))));
            }
            List<Map<String, Object>> nav = new ArrayList<>();
            if (safePage > 0)
                nav.add(TelegramClient.button("◀️", "TASK_PAGE:" + (safePage - 1)));
            if (safePage < totalPages - 1)
                nav.add(TelegramClient.button("▶️", "TASK_PAGE:" + (safePage + 1)));
            if (!nav.isEmpty())
                rows.add(nav);
            
            if (messageId != null) {
                telegram.editMessageText(chatId, messageId, sb.toString(), TelegramClient.inlineKeyboard(rows));
            } else {
                telegram.sendMessage(chatId, sb.toString(), TelegramClient.inlineKeyboard(rows));
            }
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка получения каталога.");
        }
    }

    private void showTaskCard(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) {
                telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
                return;
            }
            long subscribers = db.subscriptions().loadAll(conn).stream().filter(s -> s.taskId().equals(task.id()) && s.active())
                    .count();
            Subscription own = findUserSubscription(conn, chatId, task.id());
            String text = """
                    %s

                    Номер: %d
                    Как повторяется: %s
                    Тип: %s
                    Подписчиков: %d
                    %s
                    %s
                    """.formatted(
                    task.title(),
                    taskNumber(conn, task.id()),
                    frequencyText(task),
                    humanTaskKind(task.kind()),
                    subscribers,
                    task.note() == null || task.note().isBlank() ? "" : ("Заметка: " + task.note()),
                    own == null ? "У тебя пока нет своей настройки." : ("Твоя настройка: " + humanSchedule(own, task)));
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            if (task.kind() != TaskKind.MANUAL) {
                rows.add(
                        List.of(TelegramClient.button(own == null ? "🗓 Настроить через Mini App" : "🗓 Изменить настройку",
                                "SUB_START:" + taskNumber(conn, task.id()))));
                if (own != null)
                    rows.add(List.of(TelegramClient.button("Удалить мою настройку", "UNSUB:" + taskNumber(conn, task.id()))));
            }
            rows.add(List.of(
                    TelegramClient.button("Кто подписан", "WHO:" + taskNumber(conn, task.id())),
                    TelegramClient.button("✏️ Редактировать", "EDIT_TASK:" + taskNumber(conn, task.id()))));
            telegram.sendMessage(chatId, text.strip(), TelegramClient.inlineKeyboard(rows));
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка получения карточки дела.");
        }
    }

    private void handleWho(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) {
                telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
                return;
            }
            List<Subscription> subs = db.subscriptions().loadAll(conn).stream()
                    .filter(s -> s.taskId().equals(task.id()) && s.active())
                    .toList();
            if (subs.isEmpty()) {
                telegram.sendMessage(chatId, "На это дело пока никто не подписан.");
                return;
            }
            String body = subs.stream().map(s -> {
                try { return "• " + displayUser(conn, s.chatId()) + " — " + humanSchedule(s, task); }
                catch (Exception e) { return ""; }
            }).collect(Collectors.joining("\n\n"));
            telegram.sendMessage(chatId, "Кто подписан на «" + task.title() + "»:\n\n" + body);
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка получения данных о подписках.");
        }
    }

    private void handleTimezoneSet(long chatId, String zoneText) {
        try {
            ZoneId zone = ZoneId.of(zoneText.trim());
            try (java.sql.Connection conn = db.getConnection()) {
                UserProfile old = getProfile(conn, chatId);
                db.users().upsert(conn, new UserProfile(old.chatId(), old.username(), old.firstName(), zone.getId(),
                        old.alertsEnabled(), normalizeRepingMinutes(old.repingMinutes()),
                        old.lastTomorrowReminderForDate()));
            }
            telegram.sendMessage(chatId, "Таймзона обновлена: " + zone.getId());
        } catch (Exception e) {
            telegram.sendMessage(chatId, "Неизвестная таймзона. Примеры: Asia/Almaty, Europe/Moscow, Europe/Berlin.",
                    timezoneKeyboard());
        }
    }

    private void handleUnsub(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) {
                telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
                return;
            }
            Subscription sub = findUserSubscription(conn, chatId, task.id());
            if (sub != null) {
                db.subscriptions().delete(conn, sub.id());
                db.prompts().deleteBySubscriptionId(conn, sub.id());
            }
            telegram.sendMessage(chatId,
                    sub != null ? "Настройка удалена: " + task.title() : "У тебя не было настройки для этого дела.");
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка удаления настройки.");
        }
    }

    private void handlePromptDone(String callbackId, String promptId, long chatId, Integer messageId) {
        try (java.sql.Connection conn = db.getConnection()) {
            ActivePrompt prompt = findPrompt(conn, promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            db.prompts().delete(conn, promptId);
            
            CompletionRecord completion = new CompletionRecord(prompt.id(), prompt.subscriptionId(), prompt.taskId(),
                    prompt.chatId(), prompt.scheduledFor(), Instant.now());
            
            db.completions().upsert(conn, completion);
            
            telegram.answerCallbackQuery(callbackId, "Отмечено как сделано");
            if (messageId != null)
                telegram.editMessageReplyMarkup(chatId, messageId, TelegramClient.inlineKeyboard(List.of()));
        } catch (java.sql.SQLException e) {
            telegram.answerCallbackQuery(callbackId, "Ошибка базы данных");
        }
    }

    private void handlePromptSnooze(String callbackId, String promptId, int hours, long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            ActivePrompt prompt = findPrompt(conn, promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            db.prompts().upsert(conn, new ActivePrompt(prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(),
                    prompt.scheduledFor(),
                    Instant.now().plus(Duration.ofHours(hours)), STATE_SNOOZED, prompt.messageId(),
                    prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), null, false));
            telegram.answerCallbackQuery(callbackId, "Отложено на " + hours + " ч");
            telegram.sendMessage(chatId, "Ок, напомню через " + hours + " ч.");
        } catch (java.sql.SQLException e) {
            telegram.answerCallbackQuery(callbackId, "Ошибка базы данных");
        }
    }

    private void handlePromptGoDoing(String callbackId, String promptId, long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            ActivePrompt prompt = findPrompt(conn, promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            db.prompts().upsert(conn, new ActivePrompt(prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(),
                    prompt.scheduledFor(),
                    Instant.now().plus(GOING_DOING_CHECK_DELAY), STATE_GOING_DOING_DELAY, prompt.messageId(),
                    prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), null, false));
            telegram.answerCallbackQuery(callbackId, "Хорошо");
            telegram.sendMessage(chatId,
                    "Ок, считаю, что ты пошёл делать. Через 30 минут спрошу, сделал ли ты. Если потом молчать — буду перепингивать с твоим интервалом /reping.");
        } catch (java.sql.SQLException e) {
            telegram.answerCallbackQuery(callbackId, "Ошибка базы данных");
        }
    }

    private void sendSnoozeHoursPicker(long chatId, String promptId) {
        List<List<Map<String, Object>>> rows = List.of(
                List.of(TelegramClient.button("1 ч", "PROMPT_SN:" + promptId + ":1"),
                        TelegramClient.button("2 ч", "PROMPT_SN:" + promptId + ":2"),
                        TelegramClient.button("3 ч", "PROMPT_SN:" + promptId + ":3")),
                List.of(TelegramClient.button("6 ч", "PROMPT_SN:" + promptId + ":6"),
                        TelegramClient.button("12 ч", "PROMPT_SN:" + promptId + ":12"),
                        TelegramClient.button("24 ч", "PROMPT_SN:" + promptId + ":24")));
        telegram.sendMessage(chatId, "На сколько часов отложить?", TelegramClient.inlineKeyboard(rows));
    }

    private void sendPrompt(ActivePrompt prompt, TaskDefinition task, PromptReason reason) {
        String header = switch (reason) {
            case FIRST -> "⏰ Пора заняться делом";
            case REPEAT -> "⏰ Напоминаю ещё раз";
            case SNOOZE_FINISHED -> "⏰ Время после откладывания вышло";
            case CHECK_AFTER_WORK -> "👀 Проверяю: получилось сделать?";
        };
        try (java.sql.Connection conn = db.getConnection()) {
            String when = ZonedDateTime.ofInstant(prompt.scheduledFor(), ZoneId.of(userZone(conn, prompt.chatId())))
                    .format(DATE_TIME_FMT);
            Integer messageId = telegram.sendMessage(prompt.chatId(), header + "\n\n" + task.title() + "\nПлан: " + when,
                    promptKeyboard(prompt.id()));
            if (messageId != null) {
                db.prompts().upsert(conn, new ActivePrompt(prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(),
                        prompt.scheduledFor(), prompt.nextPingAt(), prompt.state(), messageId,
                        prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), prompt.stageStartedAt(),
                        prompt.currentStageAlertSent()));
            }
        } catch (java.sql.SQLException e) {
            System.err.println("DB error in sendPrompt: " + e.getMessage());
        }
    }

    private Map<String, Object> promptKeyboard(String promptId) {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("✅ Сделал", "PROMPT_DONE:" + promptId),
                        TelegramClient.button("🏃 Пошёл делать (30 мин)", "PROMPT_GO:" + promptId)),
                List.of(TelegramClient.button("⏳ Отложить", "PROMPT_MORE:" + promptId))));
    }

    private Subscription advanceSubscription(Subscription sub, TaskDefinition task, Instant previousScheduledFor,
            Instant now) {
        if (task.kind() == TaskKind.ONE_TIME_THIS_WEEK || task.kind() == TaskKind.ONE_TIME_NEXT_WEEK) {
            return new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(), sub.dayOfWeek(),
                    sub.dayOfMonth(), sub.zoneId(), sub.nextRunAt(), sub.active(), true, sub.daysOfWeek(), sub.daysOfMonth());
        }
        if (task.kind() == TaskKind.MANUAL) {
            return sub;
        }
        ZoneId zone = ZoneId.of(sub.zoneId());
        Instant base = previousScheduledFor.plusSeconds(1);
        Instant next = switch (task.schedule().unit()) {
            case DAY -> computeNextDaily(sub.dailyTimes(), task.schedule().interval(), zone, base);
            case WEEK -> computeNextWeekly(sub.dailyTimes(), sub.daysOfWeek(), task.schedule().interval(), zone, base);
            case MONTH -> computeNextMonthly(sub.dailyTimes(), sub.daysOfMonth(), task.schedule().interval(), zone, base);
        };
        return new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(), sub.dayOfWeek(),
                sub.dayOfMonth(), sub.zoneId(), next, sub.active(), false, sub.daysOfWeek(), sub.daysOfMonth());
    }

    private Instant computeNextDaily(List<String> times, int intervalDays, ZoneId zone, Instant base) {
        if (times == null || times.isEmpty())
            throw new IllegalArgumentException("Для ежедневного дела нужен хотя бы один момент времени");
        List<LocalTime> localTimes = times.stream().map(this::parseTime).sorted().toList();
        ZonedDateTime now = ZonedDateTime.ofInstant(base, zone);
        for (int addDays = 0; addDays <= 370; addDays++) {
            LocalDate date = now.toLocalDate().plusDays(addDays);
            long diff = Duration
                    .between(now.toLocalDate().atStartOfDay(zone).toInstant(), date.atStartOfDay(zone).toInstant())
                    .toDays();
            if (diff % intervalDays != 0)
                continue;
            for (LocalTime lt : localTimes) {
                ZonedDateTime candidate = ZonedDateTime.of(date, lt, zone);
                if (candidate.toInstant().isAfter(base))
                    return candidate.toInstant();
            }
        }
        throw new IllegalStateException("Не смог посчитать nextRunAt для ежедневного дела");
    }

    private Instant computeNextWeekly(List<String> times, List<String> daysOfWeek, int intervalWeeks, ZoneId zone, Instant base) {
        if (times == null || times.isEmpty() || daysOfWeek == null || daysOfWeek.isEmpty()) {
            throw new IllegalArgumentException("Нужно хотя бы одно время и один день недели");
        }
        List<LocalTime> localTimes = times.stream().map(this::parseTime).sorted().toList();
        List<DayOfWeek> dows = daysOfWeek.stream().map(DayOfWeek::valueOf).toList();
        
        ZonedDateTime now = ZonedDateTime.ofInstant(base, zone);
        LocalDate weekStart = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        
        for (int addDays = 0; addDays <= 1000; addDays++) {
            LocalDate date = now.toLocalDate().plusDays(addDays);
            LocalDate currentWeekStart = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            long weeksBetween = java.time.temporal.ChronoUnit.WEEKS.between(weekStart, currentWeekStart);
            
            if (weeksBetween % intervalWeeks != 0) continue;
            if (!dows.contains(date.getDayOfWeek())) continue;
            
            for (LocalTime lt : localTimes) {
                ZonedDateTime candidate = ZonedDateTime.of(date, lt, zone);
                if (candidate.toInstant().isAfter(base)) {
                    return candidate.toInstant();
                }
            }
        }
        throw new IllegalStateException("Не смог посчитать nextRunAt для еженедельного дела");
    }

    private Instant computeNextMonthly(List<String> times, List<Integer> daysOfMonth, int intervalMonths, ZoneId zone, Instant base) {
        if (times == null || times.isEmpty() || daysOfMonth == null || daysOfMonth.isEmpty()) {
            throw new IllegalArgumentException("Нужно хотя бы одно время и один день месяца");
        }
        List<LocalTime> localTimes = times.stream().map(this::parseTime).sorted().toList();
        
        ZonedDateTime now = ZonedDateTime.ofInstant(base, zone);
        LocalDate monthStart = now.toLocalDate().withDayOfMonth(1);
        
        for (int addDays = 0; addDays <= 3000; addDays++) {
            LocalDate date = now.toLocalDate().plusDays(addDays);
            LocalDate currentMonthStart = date.withDayOfMonth(1);
            long monthsBetween = java.time.temporal.ChronoUnit.MONTHS.between(monthStart, currentMonthStart);
            
            if (monthsBetween % intervalMonths != 0) continue;
            
            boolean matchDay = false;
            for (int dom : daysOfMonth) {
                if (date.getDayOfMonth() == dom || (date.getDayOfMonth() == date.lengthOfMonth() && dom > date.lengthOfMonth())) {
                    matchDay = true;
                    break;
                }
            }
            if (!matchDay) continue;
            
            for (LocalTime lt : localTimes) {
                ZonedDateTime candidate = ZonedDateTime.of(date, lt, zone);
                if (candidate.toInstant().isAfter(base)) {
                    return candidate.toInstant();
                }
            }
        }
        throw new IllegalStateException("Не смог посчитать nextRunAt для ежемесячного дела");
    }

    private UserProfile registerUser(TelegramClient.Message message) {
        long chatId = message.chat().id();
        String username = message.from() != null ? message.from().username() : null;
        String firstName = message.from() != null && message.from().firstName() != null ? message.from().firstName()
                : "User";
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile existing = db.users().findById(conn, chatId);
            UserProfile profile = existing != null
                    ? new UserProfile(chatId, username, firstName, existing.zoneId(), existing.alertsEnabled(),
                            normalizeRepingMinutes(existing.repingMinutes()), existing.lastTomorrowReminderForDate())
                    : new UserProfile(chatId, username, firstName, defaultZone.getId(), true, DEFAULT_REPING_MINUTES,
                            null);
            db.users().upsert(conn, profile);
            return profile;
        } catch (java.sql.SQLException e) {
            System.err.println("DB error: " + e.getMessage());
            return new UserProfile(chatId, username, firstName, defaultZone.getId(), true, DEFAULT_REPING_MINUTES, null);
        }
    }

    private UserProfile getProfile(java.sql.Connection conn, long chatId) throws java.sql.SQLException {
        UserProfile profile = db.users().findById(conn, chatId);
        if (profile == null) {
            profile = new UserProfile(chatId, null, "User", defaultZone.getId(), true, DEFAULT_REPING_MINUTES, null);
            db.users().upsert(conn, profile);
        }
        return profile;
    }

    private UserSession getSession(java.sql.Connection conn, long chatId) throws java.sql.SQLException {
        return db.sessions().findById(conn, chatId);
    }

    private Subscription findUserSubscription(java.sql.Connection conn, long chatId, String taskId) throws java.sql.SQLException {
        return db.subscriptions().findAllByChatId(conn, chatId).stream()
                .filter(s -> s.taskId().equals(taskId) && s.active()).findFirst().orElse(null);
    }

    private Subscription findSubscription(java.sql.Connection conn, String id) throws java.sql.SQLException {
        return db.subscriptions().findById(conn, id);
    }

    private ActivePrompt findPrompt(java.sql.Connection conn, String id) throws java.sql.SQLException {
        return db.prompts().findById(conn, id);
    }

    private TaskDefinition findTask(java.sql.Connection conn, String id) throws java.sql.SQLException {
        return db.tasks().findById(conn, id);
    }

    private TaskDefinition resolveTask(java.sql.Connection conn, String ref) throws java.sql.SQLException {
        if (ref == null || ref.isBlank())
            return null;
        String value = ref.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            int idx = Integer.parseInt(value) - 1;
            List<TaskDefinition> tasks = db.tasks().loadAll(conn);
            return idx >= 0 && idx < tasks.size() ? tasks.get(idx) : null;
        }
        return findTask(conn, value);
    }

    private int taskNumber(java.sql.Connection conn, String taskId) throws java.sql.SQLException {
        List<TaskDefinition> tasks = db.tasks().loadAll(conn);
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(taskId))
                return i + 1;
        }
        return -1;
    }

    private String frequencyText(TaskDefinition task) {
        if (task == null)
            return "—";
        String slots = task.recommendedSlots() > 1 ? " (" + task.recommendedSlots() + " раз)" : "";
        return switch (task.kind()) {
            case MANUAL -> "по кнопке, без расписания";
            case ONE_TIME_THIS_WEEK -> "один раз на этой неделе";
            case ONE_TIME_NEXT_WEEK -> "один раз на следующей неделе";
            case RECURRING -> switch (task.schedule().unit()) {
                case DAY -> "каждые " + task.schedule().interval() + " дн." + slots;
                case WEEK -> "каждые " + task.schedule().interval() + " нед." + slots;
                case MONTH -> "каждые " + task.schedule().interval() + " мес." + slots;
            };
        };
    }

    private String humanTaskKind(TaskKind kind) {
        return switch (kind) {
            case RECURRING -> "🔁 регулярное";
            case ONE_TIME_THIS_WEEK -> "🗓 разовое на этой неделе";
            case ONE_TIME_NEXT_WEEK -> "🗓 разовое на следующей неделе";
            case MANUAL -> "🖐 ручное";
        };
    }

    private String humanSchedule(Subscription sub, TaskDefinition task) {
        ZoneId zone = ZoneId.of(sub.zoneId());
        int slots = sub.dailyTimes().size();
        String slotsText = slots > 1 ? " (" + slots + " раз)" : "";
        return switch (task.kind()) {
            case MANUAL -> "без времени";
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK ->
                "один раз: " + ZonedDateTime.ofInstant(sub.nextRunAt(), zone).format(DATE_TIME_FMT);
            case RECURRING -> switch (task.schedule().unit()) {
                case DAY -> "каждые " + task.schedule().interval() + " дн. в " + String.join(", ", sub.dailyTimes())
                        + slotsText + " (" + sub.zoneId() + ")";
                case WEEK -> "каждые " + task.schedule().interval() + " нед. по " + 
                        (sub.daysOfWeek() != null ? sub.daysOfWeek().stream().map(this::dayRu).collect(Collectors.joining(", ")) : dayRu(sub.dayOfWeek())) + 
                        " в " + String.join(", ", sub.dailyTimes()) + slotsText + " (" + sub.zoneId() + ")";
                case MONTH -> "каждые " + task.schedule().interval() + " мес. " + 
                        (sub.daysOfMonth() != null ? sub.daysOfMonth().stream().map(String::valueOf).collect(Collectors.joining(", ")) : sub.dayOfMonth()) + 
                        " числа в " + String.join(", ", sub.dailyTimes()) + slotsText + " (" + sub.zoneId() + ")";
            };
        };
    }

    private String subscriptionsText(java.sql.Connection conn, long chatId) throws java.sql.SQLException {
        List<Subscription> own = db.subscriptions().findAllByChatId(conn, chatId).stream().filter(Subscription::active).toList();
        if (own.isEmpty())
            return "У тебя пока нет настроек. Открой /tasks и выбери дело.";
        StringBuilder sb = new StringBuilder("Твои настройки:\n\n");
        int i = 1;
        for (Subscription sub : own) {
            TaskDefinition task = findTask(conn, sub.taskId());
            sb.append(i++).append(". ").append(task == null ? sub.taskId() : task.title()).append("\n")
                    .append("   ").append(task == null ? "" : humanSchedule(sub, task)).append("\n");
            if (sub.nextRunAt() != null) {
                sb.append("   Ближайший пинг: ")
                        .append(ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(sub.zoneId())).format(DATE_TIME_FMT))
                        .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private String subscriptionSummary(Subscription sub, TaskDefinition task) {
        return "✅ Настройка сохранена\n\n" + task.title() + "\n" + humanSchedule(sub, task) +
                "\nБлижайший пинг: "
                + ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(sub.zoneId())).format(DATE_TIME_FMT);
    }

    private void setAlertsEnabled(long chatId, boolean enabled) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile old = getProfile(conn, chatId);
            db.users().upsert(conn, new UserProfile(old.chatId(), old.username(), old.firstName(), old.zoneId(),
                    enabled, normalizeRepingMinutes(old.repingMinutes()), old.lastTomorrowReminderForDate()));
        } catch (Exception e) {
            System.err.println("DB error: " + e.getMessage());
        }
    }

    private String alertsText(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile profile = getProfile(conn, chatId);
            return profile.alertsEnabled()
                    ? "Ты подписан на общие алерты. Бот сообщит, если кто-то долго игнорирует дело или не закроет его до конца дня."
                    : "Ты отписан от общих алертов. Бот не будет присылать тебе чужие тревожные уведомления.";
        } catch (Exception e) {
            return "Ошибка БД";
        }
    }

    private Map<String, Object> alertsKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Получать алерты", "ALERTS_ON"),
                        TelegramClient.button("Не получать", "ALERTS_OFF"))));
    }

    private void sendGlobalAlert(ActivePrompt prompt, TaskDefinition task, AlertReason reason) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile owner = db.users().findById(conn, prompt.chatId());
            String who = owner == null
                    ? "кто-то"
                    : (owner.username() != null && !owner.username().isBlank() ? "@" + owner.username()
                            : owner.firstName());
            String zoneId = owner != null ? owner.zoneId() : defaultZone.getId();
            String when = ZonedDateTime.ofInstant(prompt.scheduledFor(), ZoneId.of(zoneId))
                    .format(DATE_TIME_FMT);
            String header = switch (reason) {
                case START_IGNORED -> "🚨 Алерт: игнорирует начало дела уже 3 часа";
                case CHECK_IGNORED -> "🚨 Алерт: не подтвердил завершение после кнопки «Пошёл делать»";
                case END_OF_DAY -> "🚨 Алерт: день закончился, а дело не закрыто";
            };
            String details = switch (reason) {
                case START_IGNORED ->
                    "Человек не отреагировал на стартовый пинг. Бот уже перепингивает с заданным интервалом.";
                case CHECK_IGNORED ->
                    "Человек нажал «Пошёл делать», но потом 3 часа игнорирует проверку «Сделал?». Бот уже перепингивает с заданным интервалом.";
                case END_OF_DAY -> "Сегодня это дело должно было быть закрыто, но подтверждения так и не было.";
            };
            String text = header + "\n\n" +
                    "Дело: " + task.title() + "\n" +
                    "Кто: " + who + "\n" +
                    "Плановое время: " + when + "\n\n" +
                    details + "\n\n" +
                    "Чтобы не получать такие уведомления: /alerts off";
            for (UserProfile profile : db.users().loadAll(conn).values()) {
                if (!profile.alertsEnabled())
                    continue;
                telegram.sendMessage(profile.chatId(), text, alertsKeyboard());
            }
        } catch (Exception e) {
            System.err.println("DB error: " + e.getMessage());
        }
    }

    private String timezoneText(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            return "Твоя текущая таймзона: " + getProfile(conn, chatId).zoneId()
                    + "\nМожно выбрать кнопкой ниже или прислать /tzset Europe/Berlin";
        } catch (java.sql.SQLException e) {
            return "Ошибка БД";
        }
    }

    private Map<String, Object> timezoneKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Asia/Almaty", "TZ:Asia/Almaty"),
                        TelegramClient.button("Europe/Moscow", "TZ:Europe/Moscow")),
                List.of(TelegramClient.button("Europe/Vilnius", "TZ:Europe/Vilnius"),
                        TelegramClient.button("Europe/Berlin", "TZ:Europe/Berlin"))));
    }



    private Map<String, Object> startInlineKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Список дел", "TASK_PAGE:0"),
                        TelegramClient.button("Таймзона", "TZ_MENU")),
                List.of(TelegramClient.button("Алерты", "ALERTS_ON"),
                        TelegramClient.button("Без алертов", "ALERTS_OFF"))));
    }

    private Map<String, Object> newTaskKindKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Каждый N день", "NEW_KIND:DAY"),
                        TelegramClient.button("Каждую N неделю", "NEW_KIND:WEEK")),
                List.of(TelegramClient.button("Каждый N месяц", "NEW_KIND:MONTH")),
                List.of(TelegramClient.button("Разово на этой неделе", "NEW_KIND:THIS_WEEK"),
                        TelegramClient.button("Разово на следующей", "NEW_KIND:NEXT_WEEK")),
                List.of(TelegramClient.button("Ручное дело", "NEW_KIND:MANUAL"))));
    }

    private void handleImportDbFile(UserProfile profile, TelegramClient.Document document) {
        telegram.sendMessage(profile.chatId(), "Импорт из файла отключен, так как база данных работает напрямую с PostgreSQL.");
    }

    private void handleRepingSet(long chatId, String raw) {
        int minutes;
        try {
            minutes = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            telegram.sendMessage(chatId, "Нужно целое число минут. Например: /reping 10");
            return;
        }
        if (minutes < 1 || minutes > 180) {
            telegram.sendMessage(chatId, "Допустимо от 1 до 180 минут.");
            return;
        }
        try (java.sql.Connection conn = db.getConnection()) {
            UserProfile old = getProfile(conn, chatId);
            db.users().upsert(conn, new UserProfile(old.chatId(), old.username(), old.firstName(), old.zoneId(),
                    old.alertsEnabled(), minutes, old.lastTomorrowReminderForDate()));
        } catch (java.sql.SQLException e) {
            System.err.println("DB error: " + e.getMessage());
        }
        telegram.sendMessage(chatId, "Ок, теперь перепинг каждые " + minutes + " мин.");
    }

    private String repingText(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            return "Твой интервал перепинга: " + normalizeRepingMinutes(getProfile(conn, chatId).repingMinutes())
                    + " мин.\nИзменить: /reping 10";
        } catch (java.sql.SQLException e) {
            return "Ошибка БД";
        }
    }

    private Duration userRepingDelay(java.sql.Connection conn, long chatId) throws java.sql.SQLException {
        return Duration.ofMinutes(normalizeRepingMinutes(getProfile(conn, chatId).repingMinutes()));
    }

    private int normalizeRepingMinutes(Integer value) {
        if (value == null || value <= 0)
            return DEFAULT_REPING_MINUTES;
        return value;
    }

    private void sendChangeTaskMenu(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            List<Subscription> own = db.subscriptions().findAllByChatId(conn, chatId).stream()
                    .filter(Subscription::active)
                    .toList();
            if (own.isEmpty()) {
                telegram.sendMessage(chatId, "У тебя пока нет настроек. Открой /tasks и выбери дело.");
                return;
            }
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            for (Subscription sub : own) {
                TaskDefinition task = findTask(conn, sub.taskId());
                if (task == null)
                    continue;
                rows.add(List.of(TelegramClient.button(task.title(), "CHANGE_TASK:" + sub.id())));
            }
            telegram.sendMessage(chatId, "Выбери дело, которое хочешь изменить:", TelegramClient.inlineKeyboard(rows));
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleChangeTaskStart(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) {
                telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
                return;
            }
            Subscription sub = findUserSubscription(conn, chatId, task.id());
            if (sub == null) {
                telegram.sendMessage(chatId, "У тебя нет настройки для этого дела.");
                return;
            }
            handleChangeTaskSelect(conn, chatId, sub.id());
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleChangeTaskSelect(java.sql.Connection conn, long chatId, String subscriptionId) throws java.sql.SQLException {
        Subscription sub = findSubscription(conn, subscriptionId);
        if (sub == null || sub.chatId() != chatId) {
            telegram.sendMessage(chatId, "Настройка не найдена.");
            return;
        }
        TaskDefinition oldTask = findTask(conn, sub.taskId());
        if (oldTask == null) {
            telegram.sendMessage(chatId, "Старое дело не найдено.");
            return;
        }
        db.sessions().upsert(conn, chatId, new UserSession(SessionType.CHANGE_TASK_SELECT,
                Map.of("subscriptionId", subscriptionId), null));

        int pageSize = 10;
        List<TaskDefinition> tasks = db.tasks().loadAll(conn);
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(tasks.size(), pageSize); i++) {
            TaskDefinition task = tasks.get(i);
            if (task.id().equals(sub.taskId()))
                continue;
            rows.add(List.of(TelegramClient.button((i + 1) + ". " + trimTitle(task.title()),
                    "CHANGE_TO:" + subscriptionId + ":" + (i + 1))));
        }
        telegram.sendMessage(chatId,
                "Текущее дело: " + oldTask.title() + "\n\nВыбери новое дело или пришли номер дела:",
                TelegramClient.inlineKeyboard(rows));
    }

    private void handleChangeTaskSelect(long chatId, String subscriptionId) {
        try (java.sql.Connection conn = db.getConnection()) {
            handleChangeTaskSelect(conn, chatId, subscriptionId);
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleFastForward(long chatId) {
        try (java.sql.Connection conn = db.getConnection()) {
            List<Subscription> subs = db.subscriptions().loadAll(conn);
            int count = 0;
            int errors = 0;
            Instant now = Instant.now();
            for (Subscription sub : subs) {
                if (sub.nextRunAt() == null || !sub.nextRunAt().isBefore(now)) continue;
                TaskDefinition task = findTask(conn, sub.taskId());
                if (task == null) continue;
                if (task.kind() == TaskKind.MANUAL) continue;
                
                try {
                    Subscription current = sub;
                    while (current.nextRunAt() != null && current.nextRunAt().isBefore(now)) {
                        current = advanceSubscription(current, task, current.nextRunAt(), now);
                        if (current.oneTimeDone()) break;
                    }
                    db.subscriptions().upsert(conn, current);
                    System.out.println("[fastforward] Advanced sub " + sub.id() + " (" + task.title() + ") nextRunAt: " + sub.nextRunAt() + " -> " + current.nextRunAt());
                    count++;
                } catch (Exception e) {
                    System.err.println("[fastforward] Failed to advance sub " + sub.id() + " (" + task.title() + "): " + e.getMessage());
                    e.printStackTrace();
                    // Fallback: move nextRunAt to tomorrow to prevent infinite re-triggering
                    Instant fallback = now.plus(Duration.ofDays(1));
                    Subscription fallbackSub = new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(),
                            sub.dayOfWeek(), sub.dayOfMonth(), sub.zoneId(), fallback, sub.active(), sub.oneTimeDone(),
                            sub.daysOfWeek(), sub.daysOfMonth());
                    db.subscriptions().upsert(conn, fallbackSub);
                    System.err.println("[fastforward] Fallback nextRunAt for sub " + sub.id() + ": " + fallback);
                    errors++;
                }
            }
            db.prompts().deleteAll(conn);
            conn.commit();
            String msg = "Все долги списаны! Обновлено " + count + " подписок.";
            if (errors > 0) msg += " (" + errors + " с ошибками, сдвинуты на завтра)";
            telegram.sendMessage(chatId, msg);
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД: " + e.getMessage());
        }
    }


    private void handleChangeTaskSelectByText(long chatId, String text) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, chatId);
            if (session == null || session.data().get("subscriptionId") == null) {
                telegram.sendMessage(chatId, "Сессия потеряна. Начни заново через /changetask");
                return;
            }
            String subscriptionId = session.data().get("subscriptionId");
            handleChangeTaskComplete(conn, null, chatId, subscriptionId, text);
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleChangeTaskComplete(java.sql.Connection conn, String callbackId, long chatId, String subscriptionId, String newTaskRef) throws java.sql.SQLException {
        Subscription sub = findSubscription(conn, subscriptionId);
        if (sub == null || sub.chatId() != chatId) {
            if (callbackId != null) telegram.answerCallbackQuery(callbackId, "Настройка не найдена");
            else telegram.sendMessage(chatId, "Настройка не найдена.");
            return;
        }
        TaskDefinition newTask = resolveTask(conn, newTaskRef);
        if (newTask == null) {
            if (callbackId != null) telegram.answerCallbackQuery(callbackId, "Дело не найдено");
            else telegram.sendMessage(chatId, "Не нашёл дело: " + newTaskRef);
            return;
        }

        TaskDefinition oldTask = findTask(conn, sub.taskId());
        Subscription updated = new Subscription(
                sub.id(),
                newTask.id(),
                sub.chatId(),
                sub.dailyTimes(),
                sub.dayOfWeek(),
                sub.dayOfMonth(),
                sub.zoneId(),
                sub.nextRunAt(),
                sub.active(),
                sub.oneTimeDone(),
                sub.daysOfWeek(),
                sub.daysOfMonth());
        
        db.subscriptions().upsert(conn, updated);
        // We delete prompts associated with the old task subscription, they will be recreated by processDueItems if needed
        db.prompts().deleteBySubscriptionId(conn, subscriptionId);
        db.sessions().delete(conn, chatId);

        String message = "✅ Дело изменено\n\n" +
                "Было: " + (oldTask != null ? oldTask.title() : sub.taskId()) + "\n" +
                "Стало: " + newTask.title() + "\n\n" +
                humanSchedule(updated, newTask);
        if (callbackId != null) {
            telegram.answerCallbackQuery(callbackId, "Изменено");
        }
        telegram.sendMessage(chatId, message);
    }

    private String subscriberStatsText() {
        try (java.sql.Connection conn = db.getConnection()) {
            Map<String, Integer> taskCounts = new HashMap<>();
            for (Subscription sub : db.subscriptions().loadAll(conn)) {
                if (sub.active()) {
                    taskCounts.merge(sub.taskId(), 1, Integer::sum);
                }
            }

            if (taskCounts.isEmpty()) {
                return "Пока нет активных подписок.";
            }

            List<Map.Entry<String, Integer>> sorted = taskCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .toList();

            StringBuilder sb = new StringBuilder("📊 Статистика подписчиков:\n\n");
            int maxCount = sorted.get(0).getValue();

            for (Map.Entry<String, Integer> entry : sorted) {
                TaskDefinition task = findTask(conn, entry.getKey());
                String title = task != null ? task.title() : entry.getKey();
                int count = entry.getValue();
                int barLength = maxCount > 0 ? (count * 20) / maxCount : 0;
                String bar = "█".repeat(Math.max(1, barLength));

                sb.append(title).append("\n");
                sb.append(bar).append(" ").append(count).append(" чел.\n\n");
            }

            long total = db.subscriptions().loadAll(conn).stream().filter(Subscription::active).count();
            sb.append("Всего активных подписок: ").append(total);
            return sb.toString();
        } catch (java.sql.SQLException e) {
            return "Ошибка БД";
        }
    }

    private void sendEditTaskMenu(long chatId) {
        sendEditTaskMenu(chatId, 0, null);
    }

    private void sendEditTaskMenu(long chatId, int page, Integer messageId) {
        try (java.sql.Connection conn = db.getConnection()) {
            List<TaskDefinition> tasks = db.tasks().loadAll(conn);
            if (tasks.isEmpty()) {
                telegram.sendMessage(chatId, "Каталог пуст.");
                return;
            }
            int pageSize = 6;
            int totalPages = Math.max(1, (tasks.size() + pageSize - 1) / pageSize);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int from = safePage * pageSize;
            int to = Math.min(tasks.size(), from + pageSize);
            
            StringBuilder sb = new StringBuilder("Редактирование дел — страница ").append(safePage + 1).append("/").append(totalPages)
                    .append("\n\nВыбери дело для редактирования или пришли номер:");
            
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            for (int i = from; i < to; i++) {
                TaskDefinition task = tasks.get(i);
                rows.add(List.of(TelegramClient.button((i + 1) + ". " + trimTitle(task.title()), "EDIT_TASK:" + (i + 1))));
            }
            
            List<Map<String, Object>> nav = new ArrayList<>();
            if (safePage > 0)
                nav.add(TelegramClient.button("◀️", "EDIT_TASK_PAGE:" + (safePage - 1)));
            if (safePage < totalPages - 1)
                nav.add(TelegramClient.button("▶️", "EDIT_TASK_PAGE:" + (safePage + 1)));
            if (!nav.isEmpty())
                rows.add(nav);
            
            if (messageId != null) {
                telegram.editMessageText(chatId, messageId, sb.toString(), TelegramClient.inlineKeyboard(rows));
            } else {
                telegram.sendMessage(chatId, sb.toString(), TelegramClient.inlineKeyboard(rows));
            }
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleEditTaskStart(long chatId, String ref) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = resolveTask(conn, ref);
            if (task == null) {
                telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
                return;
            }
            handleEditTaskSelect(conn, chatId, String.valueOf(taskNumber(conn, task.id())));
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleEditTaskSelectByText(long chatId, String text) {
        try (java.sql.Connection conn = db.getConnection()) {
            handleEditTaskSelect(conn, chatId, text.trim());
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleEditTaskSelect(java.sql.Connection conn, long chatId, String ref) throws java.sql.SQLException {
        TaskDefinition task = resolveTask(conn, ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }

        db.sessions().upsert(conn, chatId, new UserSession(SessionType.EDIT_TASK_PROPERTY, Map.of("taskId", task.id()), null));

        String info = String.format("""
                Дело: %s

                Текущие параметры:
                • Название: %s
                • Тип: %s
                • Интервал: %s
                • Заметка: %s

                Что хочешь изменить?
                """,
                task.title(),
                task.title(),
                humanTaskKind(task.kind()),
                task.schedule() != null
                        ? (task.schedule().interval() + " " + task.schedule().unit().name().toLowerCase())
                        : "—",
                task.note() != null ? task.note() : "нет");

        List<List<Map<String, Object>>> rows = List.of(
                List.of(TelegramClient.button("📝 Название", "EDIT_PROP:" + task.id() + ":title")),
                List.of(TelegramClient.button("🔢 Интервал", "EDIT_PROP:" + task.id() + ":interval")),
                List.of(TelegramClient.button("📋 Заметка", "EDIT_PROP:" + task.id() + ":note")));

        telegram.sendMessage(chatId, info, TelegramClient.inlineKeyboard(rows));
    }

    private void handleEditTaskProperty(long chatId, String taskId, String property) {
        try (java.sql.Connection conn = db.getConnection()) {
            TaskDefinition task = findTask(conn, taskId);
            if (task == null) {
                telegram.sendMessage(chatId, "Дело не найдено.");
                return;
            }

            db.sessions().upsert(conn, chatId, new UserSession(SessionType.EDIT_TASK_VALUE,
                    Map.of("taskId", taskId, "property", property), null));

            String prompt = switch (property) {
                case "title" -> "Пришли новое название дела:";
                case "interval" -> {
                    if (task.schedule() == null) {
                        yield "Это дело без расписания. Нельзя изменить интервал.";
                    }
                    yield "Пришли новый интервал (число). Текущий: " + task.schedule().interval();
                }
                case "note" -> "Пришли новую заметку или - для удаления:";
                default -> "Неизвестное свойство.";
            };

            telegram.sendMessage(chatId, prompt);
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private void handleEditTaskValue(long chatId, String value) {
        try (java.sql.Connection conn = db.getConnection()) {
            UserSession session = getSession(conn, chatId);
            if (session == null || session.data().get("taskId") == null || session.data().get("property") == null) {
                telegram.sendMessage(chatId, "Сессия потеряна. Начни заново через /edittask");
                return;
            }

            String taskId = session.data().get("taskId");
            String property = session.data().get("property");

            TaskDefinition task = findTask(conn, taskId);
            if (task == null) {
                telegram.sendMessage(chatId, "Дело не найдено.");
                return;
            }

            TaskDefinition updated = switch (property) {
                case "title" -> {
                    String newTitle = value.trim();
                    if (newTitle.isBlank()) {
                        telegram.sendMessage(chatId, "Название не может быть пустым.");
                        yield null;
                    }
                    yield new TaskDefinition(task.id(), newTitle, task.kind(), task.schedule(), task.recommendedSlots(),
                            task.note());
                }
                case "interval" -> {
                    if (task.schedule() == null) {
                        telegram.sendMessage(chatId, "Это дело без расписания.");
                        yield null;
                    }
                    int newInterval;
                    try {
                        newInterval = Integer.parseInt(value.trim());
                        if (newInterval <= 0)
                            throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        telegram.sendMessage(chatId, "Нужно целое положительное число.");
                        yield null;
                    }
                    yield new TaskDefinition(task.id(), task.title(), task.kind(),
                            new ScheduleRule(task.schedule().unit(), newInterval), task.recommendedSlots(),
                            task.note());
                }
                case "note" -> {
                    String newNote = value.trim().equals("-") ? null : value.trim();
                    yield new TaskDefinition(task.id(), task.title(), task.kind(), task.schedule(),
                            task.recommendedSlots(), newNote);
                }
                default -> {
                    telegram.sendMessage(chatId, "Неизвестное свойство.");
                    yield null;
                }
            };

            if (updated != null) {
                db.tasks().upsert(conn, updated);
                db.sessions().delete(conn, chatId);
                telegram.sendMessage(chatId, "✅ Дело обновлено:\n\n" + updated.title() + "\n" + frequencyText(updated));
            }
        } catch (java.sql.SQLException e) {
            telegram.sendMessage(chatId, "Ошибка БД");
        }
    }

    private String taskBoardText() {
        try (java.sql.Connection conn = db.getConnection()) {
            List<TaskDefinition> tasks = db.tasks().loadAll(conn);
            if (tasks.isEmpty()) {
                return "Каталог пуст.";
            }

            Map<String, List<Subscription>> taskSubs = new HashMap<>();
            for (Subscription sub : db.subscriptions().loadAll(conn)) {
                if (sub.active()) {
                    taskSubs.computeIfAbsent(sub.taskId(), k -> new ArrayList<>()).add(sub);
                }
            }

            List<String> free = new ArrayList<>();
            List<String> taken = new ArrayList<>();

            for (TaskDefinition task : tasks) {
                List<Subscription> subs = taskSubs.getOrDefault(task.id(), List.of());
                int slots = task.recommendedSlots();

                if (subs.isEmpty()) {
                    free.add("• " + task.title() + " (0/" + slots + ")");
                } else if (subs.size() < slots) {
                    String who = subs.stream()
                            .map(s -> {
                                try {
                                    return displayUser(conn, s.chatId()).split(" ")[0];
                                } catch (java.sql.SQLException e) {
                                    return "Ошибка";
                                }
                            })
                            .collect(Collectors.joining(", "));
                    if (who.isEmpty()) who = "кто-то";
                    taken.add("• " + task.title() + " (" + subs.size() + "/" + slots + ") — " + who);
                } else {
                    String who = subs.stream()
                            .map(s -> {
                                try {
                                    return displayUser(conn, s.chatId()).split(" ")[0];
                                } catch (java.sql.SQLException e) {
                                    return "Ошибка";
                                }
                            })
                            .collect(Collectors.joining(", "));
                    if (who.isEmpty()) who = "кто-то";
                    taken.add("• " + task.title() + " (" + subs.size() + "/" + slots + ") ✅ — " + who);
                }
            }

            StringBuilder sb = new StringBuilder("📋 Доска дел:\n\n");

            if (!taken.isEmpty()) {
                sb.append("🔒 Забранные дела:\n");
                sb.append(String.join("\n", taken));
                sb.append("\n\n");
            }

            if (!free.isEmpty()) {
                sb.append("🆓 Свободные дела:\n");
                sb.append(String.join("\n", free));
            }

            return sb.toString().strip();
        } catch (java.sql.SQLException e) {
            return "Ошибка БД";
        }
    }

    private void collectTomorrowReminders(java.sql.Connection conn, Instant now, List<TomorrowReminderAction> out) throws java.sql.SQLException {
        for (UserProfile profile : db.users().loadAll(conn).values()) {
            ZoneId zone = ZoneId.of(profile.zoneId());
            ZonedDateTime localNow = ZonedDateTime.ofInstant(now, zone);
            LocalTime time = localNow.toLocalTime();
            if (time.isBefore(TOMORROW_REMINDER_TIME)
                    || time.isAfter(TOMORROW_REMINDER_TIME.plus(TOMORROW_REMINDER_WINDOW))) {
                continue;
            }
            LocalDate tomorrow = localNow.toLocalDate().plusDays(1);
            if (tomorrow.toString().equals(profile.lastTomorrowReminderForDate())) {
                continue;
            }
            List<String> items = new ArrayList<>();
            for (Subscription sub : db.subscriptions().findAllByChatId(conn, profile.chatId())) {
                if (!sub.active() || sub.oneTimeDone() || sub.nextRunAt() == null)
                    continue;
                TaskDefinition task = findTask(conn, sub.taskId());
                if (task == null || task.kind() == TaskKind.MANUAL)
                    continue;
                LocalDate dueDate = ZonedDateTime.ofInstant(sub.nextRunAt(), zone).toLocalDate();
                if (!dueDate.equals(tomorrow))
                    continue;
                String schedule = sub.dailyTimes().isEmpty() ? "без времени" : String.join(", ", sub.dailyTimes());
                items.add("• " + task.title() + " — " + schedule);
            }
            UserProfile updated = new UserProfile(profile.chatId(), profile.username(), profile.firstName(),
                    profile.zoneId(), profile.alertsEnabled(), normalizeRepingMinutes(profile.repingMinutes()),
                    tomorrow.toString());
            db.users().upsert(conn, updated);
            if (!items.isEmpty()) {
                String text = "📋 Завтра у тебя такие дела:\n\n" + String.join("\n", items);
                out.add(new TomorrowReminderAction(profile.chatId(), text));
            }
        }
    }


    private String todayBoardText() {
        try (java.sql.Connection conn = db.getConnection()) {
            String sql = """
                WITH today_prompts AS (
                    SELECT DISTINCT ON (p.subscription_id)
                        p.subscription_id, p.state, p.next_ping_at
                    FROM prompts p
                    JOIN subscriptions s ON s.id = p.subscription_id
                    JOIN users u ON u.chat_id = s.chat_id
                    WHERE p.scheduled_for BETWEEN NOW() - INTERVAL '2 days' AND NOW() + INTERVAL '1 day'
                      AND (p.scheduled_for AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date
                    ORDER BY p.subscription_id, p.scheduled_for DESC
                ),
                today_completions AS (
                    SELECT cr.subscription_id, MAX(cr.completed_at) AS last_completed_at
                    FROM completion_records cr
                    JOIN subscriptions s ON s.id = cr.subscription_id
                    JOIN users u ON u.chat_id = s.chat_id
                    WHERE cr.scheduled_for BETWEEN NOW() - INTERVAL '2 days' AND NOW() + INTERVAL '1 day'
                      AND (cr.scheduled_for AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date
                    GROUP BY cr.subscription_id
                )
                SELECT
                    u.chat_id, u.username, u.first_name, u.zone_id,
                    t.title AS task_title,
                    s.next_run_at,
                    tp.state AS prompt_state,
                    tp.next_ping_at AS prompt_next_ping,
                    tc.last_completed_at,
                    CASE WHEN s.next_run_at IS NOT NULL
                         AND s.next_run_at BETWEEN NOW() - INTERVAL '1 day' AND NOW() + INTERVAL '1 day'
                         AND (s.next_run_at AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date
                         THEN true ELSE false END AS has_today_future
                FROM subscriptions s
                JOIN tasks t ON t.id = s.task_id AND t.active = true
                JOIN users u ON u.chat_id = s.chat_id
                LEFT JOIN today_prompts tp ON tp.subscription_id = s.id
                LEFT JOIN today_completions tc ON tc.subscription_id = s.id
                WHERE s.active = true AND t.kind != 'MANUAL'
                  AND (tp.subscription_id IS NOT NULL
                       OR tc.subscription_id IS NOT NULL
                       OR (s.next_run_at IS NOT NULL
                           AND s.next_run_at BETWEEN NOW() - INTERVAL '1 day' AND NOW() + INTERVAL '1 day'
                           AND (s.next_run_at AT TIME ZONE u.zone_id)::date = (NOW() AT TIME ZONE u.zone_id)::date))
                ORDER BY COALESCE(u.username, u.first_name, u.chat_id::text), t.title
                """;
            Map<Long, List<String>> userSections = new LinkedHashMap<>();
            Map<Long, String> userNames = new LinkedHashMap<>();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long uid = rs.getLong("chat_id");
                    String username = rs.getString("username");
                    String firstName = rs.getString("first_name");
                    String zoneId = rs.getString("zone_id");
                    String taskTitle = rs.getString("task_title");
                    String promptState = rs.getString("prompt_state");
                    java.sql.Timestamp promptNextPing = rs.getTimestamp("prompt_next_ping");
                    java.sql.Timestamp lastCompleted = rs.getTimestamp("last_completed_at");
                    java.sql.Timestamp nextRunAt = rs.getTimestamp("next_run_at");

                    ZoneId zone;
                    try { zone = ZoneId.of(zoneId); } catch (Exception e) { zone = defaultZone; }

                    String status;
                    if (promptState != null) {
                        status = switch (promptState) {
                            case STATE_SNOOZED -> "отложено до " + DATE_TIME_FMT.format(
                                    ZonedDateTime.ofInstant(promptNextPing.toInstant(), zone));
                            case STATE_GOING_DOING_DELAY -> "пошёл делать, проверка в " + DATE_TIME_FMT.format(
                                    ZonedDateTime.ofInstant(promptNextPing.toInstant(), zone));
                            case STATE_CHECK_WAITING -> "ждёт подтверждения";
                            default -> "ждёт ответа";
                        };
                    } else if (lastCompleted != null) {
                        status = "сделано в " + DATE_TIME_FMT.format(
                                ZonedDateTime.ofInstant(lastCompleted.toInstant(), zone));
                    } else {
                        status = "запланировано на " + DATE_TIME_FMT.format(
                                ZonedDateTime.ofInstant(nextRunAt.toInstant(), zone));
                    }

                    String displayName = (username != null && !username.isBlank())
                            ? "@" + username : firstName + " (" + uid + ")";
                    userNames.putIfAbsent(uid, displayName);
                    userSections.computeIfAbsent(uid, k -> new ArrayList<>())
                            .add("• " + taskTitle + " — " + status);
                }
            }
            if (userSections.isEmpty()) return "На сегодня ни у кого нет активных дел.";
            List<String> sections = new ArrayList<>();
            for (var entry : userSections.entrySet()) {
                sections.add(userNames.get(entry.getKey()) + "\n" + String.join("\n", entry.getValue()));
            }
            return "Дела на сегодня:\n\n" + String.join("\n\n", sections);
        } catch (java.sql.SQLException e) {
            return "Ошибка БД";
        }
    }

    private String displayNameForSort(UserProfile profile) {
        if (profile.username() != null && !profile.username().isBlank())
            return profile.username();
        return profile.firstName() == null ? String.valueOf(profile.chatId()) : profile.firstName();
    }



    private String startText() {
        return """
                \uD83D\uDC4B Привет! Я бот-напоминалка по делам.

                \uD83D\uDCF1 Нажми «Открыть приложение» ниже — там всё можно сделать удобно: посмотреть дела, подписаться, настроить расписание, изменить настройки.

                Или используй команды:
                /tasks — каталог дел
                /subs — мои подписки
                /new — создать новое дело
                /help — справка по всем командам
                """;
    }

    private String helpText() {
        return """
                \uD83D\uDCD6 *Справка*

                \uD83D\uDCF1 *Приложение*
                Нажми /start и «Открыть приложение» — через Mini App можно делать всё то же, что через команды, но удобнее.

                \uD83D\uDCCB *Дела и подписки*
                /tasks — каталог всех дел
                /task 5 — карточка дела по номеру
                /subs — мои текущие подписки
                /changetask — заменить дело в подписке
                /new — создать новое дело через диалог
                /edittask — изменить название, интервал или заметку дела

                \u23F0 *Напоминания*
                Когда приходит напоминание, есть кнопки:
                • Сделал — закрыть дело
                • Пошёл делать — проверю через 30 мин
                • Отложить — поставлю на паузу
                Если не отвечаешь — перепингую с интервалом /reping.
                Через 3 часа игнора — алерт всем подписчикам алертов.

                \u2699\uFE0F *Настройки*
                /tz — посмотреть/сменить таймзону
                /reping — показать интервал перепинга
                /reping 10 — перепинг каждые 10 минут
                /alerts — статус алертов
                /alerts on · /alerts off

                \uD83D\uDCCA *Обзор*
                /today — дела на сегодня у всех
                /board — доска забранных/свободных дел
                /stats — статистика подписчиков

                \uD83D\uDCC1 *Импорт/экспорт*
                /import — загрузить каталог JSON-файлом
                /importdb — загрузить старый export файл
                /exportdb — выгрузить данные

                /cancel — отменить текущий сценарий
                """;
    }

    private String userZone(java.sql.Connection conn, long chatId) throws java.sql.SQLException {
        UserProfile p = getProfile(conn, chatId);
        return p != null ? p.zoneId() : defaultZone.getId();
    }

    private String displayUser(java.sql.Connection conn, long chatId) throws java.sql.SQLException {
        UserProfile user = getProfile(conn, chatId);
        if (user.username() != null && !user.username().isBlank())
            return "@" + user.username();
        return user.firstName() + " (" + chatId + ")";
    }

    private String trimTitle(String title) {
        return title.length() <= 28 ? title : title.substring(0, 25) + "...";
    }

    private LocalTime parseTime(String raw) {
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("H:mm"),
                DateTimeFormatter.ofPattern("HH:mm"),
                DateTimeFormatter.ofPattern("H:mm:ss"),
                DateTimeFormatter.ofPattern("HH:mm:ss"));
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalTime.parse(raw.trim(), formatter).withSecond(0).withNano(0);
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Неверный формат времени: " + raw);
    }

    private String slugify(String text) {
        String slug = text.toLowerCase(Locale.ROOT)
                .replaceAll("[а-яё]", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "task-" + shortId().substring(0, 6) : slug;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String dayRu(String day) {
        try {
            return DayOfWeek.valueOf(day).getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"));
        } catch (Exception e) {
            return day;
        }
    }

    private String minutesLabel(int minutes) {
        if (minutes == 60)
            return "1 час";
        if (minutes < 60)
            return minutes + " мин";
        return (minutes / 60) + " ч";
    }

    private record DueAction(ActivePrompt prompt, TaskDefinition task, PromptReason reason) {
    }

    private record AlertAction(ActivePrompt prompt, TaskDefinition task, AlertReason reason) {
    }

    private record TomorrowReminderAction(long chatId, String text) {
    }

    private enum PromptReason {
        FIRST, REPEAT, SNOOZE_FINISHED, CHECK_AFTER_WORK
    }

    private enum AlertReason {
        START_IGNORED, CHECK_IGNORED, END_OF_DAY
    }
}
