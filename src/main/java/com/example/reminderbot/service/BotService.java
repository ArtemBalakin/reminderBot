package com.example.reminderbot.service;

import com.example.reminderbot.model.*;
import com.example.reminderbot.storage.Store;
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
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Duration IGNORE_REPING_DELAY = Duration.ofMinutes(5);
    private static final Duration GOING_DOING_CHECK_DELAY = Duration.ofMinutes(30);
    private static final Duration IGNORE_ALERT_DELAY = Duration.ofMinutes(30);
    private static final String STATE_START_WAITING = "START_WAITING";
    private static final String STATE_SNOOZED = "SNOOZED";
    private static final String STATE_GOING_DOING_DELAY = "GOING_DOING_DELAY";
    private static final String STATE_CHECK_WAITING = "CHECK_WAITING";

    private final TelegramClient telegram;
    private final Store<BotState> stateStore;
    private final Store<Catalog> catalogStore;
    private final ZoneId defaultZone;
    private final String appBaseUrl;

    private BotState state;
    private Catalog catalog;

    public BotService(TelegramClient telegram,
                      Store<BotState> stateStore,
                      Store<Catalog> catalogStore,
                      ZoneId defaultZone,
                      String appBaseUrl) {
        this.telegram = telegram;
        this.stateStore = stateStore;
        this.catalogStore = catalogStore;
        this.defaultZone = defaultZone;
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        this.state = stateStore.load();
        this.catalog = catalogStore.load();
    }

    public synchronized long getLastUpdateId() {
        return state.lastUpdateId();
    }

    public synchronized void setLastUpdateId(long updateId) {
        state = new BotState(state.users(), state.subscriptions(), state.prompts(), state.sessions(), updateId);
        saveState();
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
        synchronized (this) {
            Instant now = Instant.now();
            Set<String> blockedSubs = state.prompts().stream().map(ActivePrompt::subscriptionId).collect(Collectors.toSet());

            for (Subscription sub : state.subscriptions()) {
                if (!sub.active() || sub.oneTimeDone() || sub.nextRunAt() == null || sub.nextRunAt().isAfter(now)) {
                    continue;
                }
                if (blockedSubs.contains(sub.id())) {
                    continue;
                }
                TaskDefinition task = findTask(sub.taskId());
                if (task == null) continue;
                ActivePrompt prompt = new ActivePrompt(
                        shortId(), sub.id(), sub.taskId(), sub.chatId(), sub.nextRunAt(), now.plus(IGNORE_REPING_DELAY),
                        STATE_START_WAITING, null, 0, false, now, false
                );
                state.prompts().add(prompt);
                blockedSubs.add(sub.id());
                actions.add(new DueAction(prompt, task, PromptReason.FIRST));
            }

            for (int i = 0; i < state.prompts().size(); i++) {
                ActivePrompt prompt = state.prompts().get(i);
                TaskDefinition task = findTask(prompt.taskId());
                if (task == null) continue;

                ZoneId zone = ZoneId.of(userZone(prompt.chatId()));
                LocalDate scheduledDate = ZonedDateTime.ofInstant(prompt.scheduledFor(), zone).toLocalDate();
                LocalDate today = ZonedDateTime.ofInstant(now, zone).toLocalDate();
                if (!prompt.endOfDayAlertSent() && today.isAfter(scheduledDate)) {
                    ActivePrompt updated = new ActivePrompt(
                            prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(), prompt.nextPingAt(),
                            prompt.state(), prompt.messageId(), prompt.alertBroadcastCount(), true, prompt.stageStartedAt(), prompt.currentStageAlertSent()
                    );
                    state.prompts().set(i, updated);
                    alerts.add(new AlertAction(updated, task, AlertReason.END_OF_DAY));
                    prompt = updated;
                }

                if (prompt.nextPingAt() == null || prompt.nextPingAt().isAfter(now)) continue;

                if (STATE_SNOOZED.equals(prompt.state())) {
                    ActivePrompt updated = new ActivePrompt(
                            prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(), now.plus(IGNORE_REPING_DELAY),
                            STATE_START_WAITING, prompt.messageId(), prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), now, false
                    );
                    state.prompts().set(i, updated);
                    actions.add(new DueAction(updated, task, PromptReason.SNOOZE_FINISHED));
                    continue;
                }

                if (STATE_GOING_DOING_DELAY.equals(prompt.state())) {
                    ActivePrompt updated = new ActivePrompt(
                            prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(), now.plus(IGNORE_REPING_DELAY),
                            STATE_CHECK_WAITING, prompt.messageId(), prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), now, false
                    );
                    state.prompts().set(i, updated);
                    actions.add(new DueAction(updated, task, PromptReason.CHECK_AFTER_WORK));
                    continue;
                }

                boolean isStartStage = STATE_START_WAITING.equals(prompt.state());
                boolean isCheckStage = STATE_CHECK_WAITING.equals(prompt.state());
                if (!isStartStage && !isCheckStage) continue;

                Instant stageStartedAt = prompt.stageStartedAt() == null ? now : prompt.stageStartedAt();
                boolean shouldBroadcast = !prompt.currentStageAlertSent() && !Duration.between(stageStartedAt, now).minus(IGNORE_ALERT_DELAY).isNegative();
                ActivePrompt updated = new ActivePrompt(
                        prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(), now.plus(IGNORE_REPING_DELAY),
                        prompt.state(), prompt.messageId(), prompt.alertBroadcastCount() + (shouldBroadcast ? 1 : 0),
                        prompt.endOfDayAlertSent(), stageStartedAt, prompt.currentStageAlertSent() || shouldBroadcast
                );
                state.prompts().set(i, updated);
                actions.add(new DueAction(updated, task, isStartStage ? PromptReason.REPEAT : PromptReason.CHECK_AFTER_WORK));
                if (shouldBroadcast) {
                    alerts.add(new AlertAction(updated, task, isStartStage ? AlertReason.START_IGNORED : AlertReason.CHECK_IGNORED));
                }
            }
            saveState();
        }

        for (DueAction action : actions) {
            sendPrompt(action.prompt(), action.task(), action.reason());
        }
        for (AlertAction alert : alerts) {
            sendGlobalAlert(alert.prompt(), alert.task(), alert.reason());
        }
    }

    private void handleMessage(TelegramClient.Message message) {
        UserProfile profile = registerUser(message);
        long chatId = profile.chatId();

        if (message.webAppData() != null && message.webAppData().data() != null) {
            handleWebAppPayload(profile, message);
            return;
        }

        UserSession session = synchronizedSession(chatId);
        if (session != null && session.type() == SessionType.IMPORT_CATALOG_FILE && message.document() != null) {
            handleImportFile(profile, message.document());
            return;
        }

        String text = message.text() == null ? "" : message.text().trim();
        if (text.equals("/cancel")) {
            clearSession(chatId);
            telegram.sendMessage(chatId, "Ок, отменил текущий сценарий.", TelegramClient.removeKeyboard());
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
                default -> {
                }
            }
        }

        if (text.isBlank()) {
            return;
        }

        if (text.equals("/start")) {
            telegram.sendMessage(chatId, startText(), startKeyboard());
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
            telegram.sendMessage(chatId, subscriptionsText(chatId));
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
            state.sessions().put(chatId, UserSession.of(SessionType.IMPORT_CATALOG_FILE));
            saveState();
            telegram.sendMessage(chatId,
                    "Пришли одним следующим сообщением JSON-файл каталога. Формат: объект с полем tasks.\\n\\n" +
                            "Например: {\"tasks\":[{\"title\":\"Мыть пол\",...}]}");
            return;
        }
        if (text.equals("/new")) {
            state.sessions().put(chatId, UserSession.of(SessionType.NEW_TASK_TITLE));
            saveState();
            telegram.sendMessage(chatId, "Как назвать новое дело? Просто пришли название одним сообщением.");
            return;
        }
        if (text.equals("/reload")) {
            synchronized (this) { this.catalog = catalogStore.load(); }
            telegram.sendMessage(chatId, "Каталог перечитан. Дел: " + catalog.tasks().size());
            return;
        }

        telegram.sendMessage(chatId, "Не понял. Нажми /tasks, /new или /help.", startKeyboard());
    }

    private void handleCallback(TelegramClient.CallbackQuery callback) {
        long chatId = callback.message() != null && callback.message().chat() != null
                ? callback.message().chat().id()
                : callback.from().id();
        String data = callback.data() == null ? "" : callback.data();
        try {
            if (data.startsWith("TASK_PAGE:")) {
                sendTasksPage(chatId, Integer.parseInt(data.substring(10)));
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
        } catch (Exception e) {
            telegram.answerCallbackQuery(callback.id(), "Ошибка: " + e.getMessage());
            return;
        }
        telegram.answerCallbackQuery(callback.id(), "Неизвестная кнопка");
    }

    private void handleImportFile(UserProfile profile, TelegramClient.Document document) {
        try {
            String filePath = telegram.getFilePath(document.fileId());
            byte[] bytes = telegram.downloadFile(filePath);
            Catalog imported = stateStore.mapper().readValue(new String(bytes, StandardCharsets.UTF_8), Catalog.class);
            synchronized (this) {
                this.catalog = imported;
                catalogStore.save(imported);
                state.sessions().remove(profile.chatId());
                saveState();
            }
            telegram.sendMessage(profile.chatId(), "Файл импортирован. Дел: " + imported.tasks().size(), TelegramClient.removeKeyboard());
        } catch (Exception e) {
            telegram.sendMessage(profile.chatId(), "Не смог импортировать файл: " + e.getMessage());
        }
    }

    private void handleWebAppPayload(UserProfile profile, TelegramClient.Message message) {
        long chatId = profile.chatId();
        UserSession session = synchronizedSession(chatId);
        if (session == null || session.type() != SessionType.WAITING_WEBAPP_SUBSCRIPTION) {
            telegram.sendMessage(chatId, "Планировщик открыт вне сценария настройки. Открой настройку заново через /tasks.", TelegramClient.removeKeyboard());
            return;
        }
        try {
            JsonNode root = stateStore.mapper().readTree(message.webAppData().data());
            String type = root.path("type").asText();
            if (!"subscription".equals(type)) {
                throw new IllegalArgumentException("Ожидался payload subscription");
            }
            String taskId = root.path("taskId").asText();
            TaskDefinition task = findTask(taskId);
            if (task == null) throw new IllegalArgumentException("Дело не найдено");
            Subscription updated;
            if ("daily".equals(root.path("mode").asText())) {
                List<String> times = new ArrayList<>();
                for (JsonNode item : root.path("times")) times.add(parseTime(item.asText()).format(TIME_FMT));
                if (times.isEmpty()) throw new IllegalArgumentException("Нужно хотя бы одно время");
                updated = buildDailySubscription(profile, task, times);
            } else {
                LocalDate date = LocalDate.parse(root.path("date").asText());
                LocalTime time = parseTime(root.path("time").asText());
                updated = buildDatedSubscription(profile, task, date, time);
            }
            synchronized (this) {
                replaceSubscription(updated);
                state.sessions().remove(chatId);
                saveState();
            }
            if (session.helperMessageId() != null) {
                telegram.deleteMessage(chatId, session.helperMessageId());
            }
            if (message.messageId() != null) {
                telegram.deleteMessage(chatId, message.messageId());
            }
            telegram.sendMessage(chatId, subscriptionSummary(updated, task), TelegramClient.removeKeyboard());
        } catch (Exception e) {
            telegram.sendMessage(chatId, "Не смог сохранить настройку из Mini App: " + e.getMessage(), TelegramClient.removeKeyboard());
        }
    }

    private void handleNewTaskTitle(UserProfile profile, String text) {
        UserSession session = synchronizedSession(profile.chatId());
        Map<String, String> data = session.data();
        data.put("title", text.trim());
        state.sessions().put(profile.chatId(), new UserSession(SessionType.NEW_TASK_KIND, data, session.helperMessageId()));
        saveState();
        telegram.sendMessage(profile.chatId(), "Какое правило у этого дела?", newTaskKindKeyboard());
    }

    private void handleNewKind(long chatId, String kindCode) {
        UserSession session = synchronizedSession(chatId);
        if (session == null || (session.type() != SessionType.NEW_TASK_KIND && session.type() != SessionType.NEW_TASK_INTERVAL)) {
            telegram.sendMessage(chatId, "Сначала нажми /new");
            return;
        }
        Map<String, String> data = session.data();
        switch (kindCode) {
            case "DAY" -> {
                data.put("kind", TaskKind.RECURRING.name());
                data.put("unit", FrequencyUnit.DAY.name());
                state.sessions().put(chatId, new UserSession(SessionType.NEW_TASK_INTERVAL, data, session.helperMessageId()));
                saveState();
                telegram.sendMessage(chatId, "Раз в сколько дней повторять? Пришли число, например 1 или 2.");
            }
            case "WEEK" -> {
                data.put("kind", TaskKind.RECURRING.name());
                data.put("unit", FrequencyUnit.WEEK.name());
                state.sessions().put(chatId, new UserSession(SessionType.NEW_TASK_INTERVAL, data, session.helperMessageId()));
                saveState();
                telegram.sendMessage(chatId, "Раз в сколько недель повторять? Пришли число, например 1 или 3.");
            }
            case "MONTH" -> {
                data.put("kind", TaskKind.RECURRING.name());
                data.put("unit", FrequencyUnit.MONTH.name());
                state.sessions().put(chatId, new UserSession(SessionType.NEW_TASK_INTERVAL, data, session.helperMessageId()));
                saveState();
                telegram.sendMessage(chatId, "Раз в сколько месяцев повторять? Пришли число, например 1 или 2.");
            }
            case "THIS_WEEK" -> saveNewTask(chatId, data, TaskKind.ONE_TIME_THIS_WEEK, null, null, 1);
            case "NEXT_WEEK" -> saveNewTask(chatId, data, TaskKind.ONE_TIME_NEXT_WEEK, null, null, 1);
            case "MANUAL" -> saveNewTask(chatId, data, TaskKind.MANUAL, null, null, 0);
            default -> telegram.sendMessage(chatId, "Неизвестный тип.");
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
        UserSession session = synchronizedSession(profile.chatId());
        Map<String, String> data = session.data();
        data.put("interval", String.valueOf(interval));
        state.sessions().put(profile.chatId(), new UserSession(SessionType.NEW_TASK_NOTE, data, session.helperMessageId()));
        saveState();
        telegram.sendMessage(profile.chatId(), "Нужна короткая заметка? Пришли текст или просто - если без заметки.");
    }

    private void handleNewTaskNote(UserProfile profile, String text) {
        UserSession session = synchronizedSession(profile.chatId());
        Map<String, String> data = session.data();
        String note = text.trim().equals("-") ? null : text.trim();
        FrequencyUnit unit = FrequencyUnit.valueOf(data.get("unit"));
        int interval = Integer.parseInt(data.get("interval"));
        int slots = unit == FrequencyUnit.DAY ? 1 : 1;
        saveNewTask(profile.chatId(), data, TaskKind.RECURRING, unit, note, slots, interval);
    }

    private void saveNewTask(long chatId, Map<String, String> data, TaskKind kind, FrequencyUnit unit, String note, int slots) {
        saveNewTask(chatId, data, kind, unit, note, slots, 1);
    }

    private void saveNewTask(long chatId, Map<String, String> data, TaskKind kind, FrequencyUnit unit, String note, int slots, int interval) {
        String title = data.get("title");
        String id = slugify(title);
        synchronized (this) {
            if (findTask(id) != null) {
                id = id + "-" + shortId().substring(0, 4);
            }
            TaskDefinition task = new TaskDefinition(
                    id,
                    title,
                    kind,
                    unit == null ? null : new ScheduleRule(unit, interval),
                    slots,
                    note
            );
            catalog.tasks().add(task);
            catalogStore.save(catalog);
            state.sessions().remove(chatId);
            saveState();
        }
        telegram.sendMessage(chatId,
                "Добавил новое дело:\n\n" + title + "\nПравило: " + frequencyText(findTask(id)) +
                        "\nДальше можешь открыть /tasks и настроить себе напоминание.",
                TelegramClient.removeKeyboard());
    }

    private void startMiniAppSubscription(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело.");
            return;
        }
        if (task.kind() == TaskKind.MANUAL) {
            telegram.sendMessage(chatId, "Это ручное дело без расписания. Для него нет подписки по времени.");
            return;
        }
        UserProfile profile = synchronizedProfile(chatId);
        String url = miniAppUrl(task, profile.zoneId());
        Map<String, Object> keyboard = TelegramClient.keyboard(List.of(
                List.of(TelegramClient.webAppKeyboardButton("Открыть планировщик", url)),
                List.of(Map.of("text", "/cancel"))
        ), true, true);
        Integer msgId = telegram.sendMessage(chatId,
                "Открой планировщик ниже. Внутри выбери дату и время, затем нажми «Сохранить».\n\n" +
                        "Для ежедневных дел можно добавить несколько времён сразу.",
                keyboard,
                false);
        state.sessions().put(chatId, new UserSession(SessionType.WAITING_WEBAPP_SUBSCRIPTION,
                Map.of("taskId", task.id()), msgId));
        saveState();
    }

    private String miniAppUrl(TaskDefinition task, String zoneId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("taskId", task.id());
        params.put("title", task.title());
        params.put("kind", task.kind().name());
        params.put("zone", zoneId);
        if (task.schedule() != null) {
            params.put("unit", task.schedule().unit().name());
            params.put("interval", String.valueOf(task.schedule().interval()));
        }
        return appBaseUrl + "/miniapp?" + params.entrySet().stream()
                .map(e -> TelegramClient.encode(e.getKey()) + "=" + TelegramClient.encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private Subscription buildDailySubscription(UserProfile profile, TaskDefinition task, List<String> times) {
        ZoneId zone = ZoneId.of(profile.zoneId());
        Subscription existing = findUserSubscription(profile.chatId(), task.id());
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
                false
        );
    }

    private Subscription buildDatedSubscription(UserProfile profile, TaskDefinition task, LocalDate date, LocalTime time) {
        ZoneId zone = ZoneId.of(profile.zoneId());
        ZonedDateTime chosen = ZonedDateTime.of(date, time, zone);
        if (!chosen.toInstant().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Этот момент уже прошёл");
        }
        Subscription existing = findUserSubscription(profile.chatId(), task.id());
        return switch (task.kind()) {
            case RECURRING -> {
                if (task.schedule().unit() == FrequencyUnit.WEEK) {
                    yield new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                            List.of(time.format(TIME_FMT)), date.getDayOfWeek().name(), null, profile.zoneId(), chosen.toInstant(), true, false);
                } else if (task.schedule().unit() == FrequencyUnit.MONTH) {
                    yield new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                            List.of(time.format(TIME_FMT)), null, date.getDayOfMonth(), profile.zoneId(), chosen.toInstant(), true, false);
                } else {
                    yield new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                            List.of(time.format(TIME_FMT)), null, null, profile.zoneId(), chosen.toInstant(), true, false);
                }
            }
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK -> new Subscription(existing == null ? shortId() : existing.id(), task.id(), profile.chatId(),
                    List.of(time.format(TIME_FMT)), null, null, profile.zoneId(), chosen.toInstant(), true, false);
            case MANUAL -> throw new IllegalArgumentException("Для ручного дела нельзя задать дату");
        };
    }

    private void sendTasksPage(long chatId, int page) {
        List<TaskDefinition> tasks = catalog.tasks();
        if (tasks.isEmpty()) {
            telegram.sendMessage(chatId, "Каталог пуст. Пришли файл через /import или создай дело через /new.");
            return;
        }
        int pageSize = 6;
        int totalPages = Math.max(1, (tasks.size() + pageSize - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * pageSize;
        int to = Math.min(tasks.size(), from + pageSize);
        StringBuilder sb = new StringBuilder("Дела — страница ").append(safePage + 1).append("/").append(totalPages).append("\n\n");
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(". ").append(tasks.get(i).title()).append("\n   ")
                    .append(frequencyText(tasks.get(i))).append("\n\n");
        }
        sb.append("Нажми на дело, чтобы открыть карточку и настроить себе напоминание.");

        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(List.of(TelegramClient.button((i + 1) + ". " + trimTitle(tasks.get(i).title()), "TASK_SHOW:" + (i + 1))));
        }
        List<Map<String, Object>> nav = new ArrayList<>();
        if (safePage > 0) nav.add(TelegramClient.button("◀️", "TASK_PAGE:" + (safePage - 1)));
        if (safePage < totalPages - 1) nav.add(TelegramClient.button("▶️", "TASK_PAGE:" + (safePage + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        telegram.sendMessage(chatId, sb.toString(), TelegramClient.inlineKeyboard(rows));
    }

    private void showTaskCard(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        long subscribers = state.subscriptions().stream().filter(s -> s.taskId().equals(task.id()) && s.active()).count();
        Subscription own = findUserSubscription(chatId, task.id());
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
                taskNumber(task.id()),
                frequencyText(task),
                humanTaskKind(task.kind()),
                subscribers,
                task.note() == null || task.note().isBlank() ? "" : ("Заметка: " + task.note()),
                own == null ? "У тебя пока нет своей настройки." : ("Твоя настройка: " + humanSchedule(own, task))
        );
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        if (task.kind() != TaskKind.MANUAL) {
            rows.add(List.of(TelegramClient.button(own == null ? "🗓 Настроить через Mini App" : "🗓 Изменить настройку", "SUB_START:" + taskNumber(task.id()))));
            if (own != null) rows.add(List.of(TelegramClient.button("Удалить мою настройку", "UNSUB:" + taskNumber(task.id()))));
        }
        rows.add(List.of(TelegramClient.button("Кто подписан", "WHO:" + taskNumber(task.id()))));
        telegram.sendMessage(chatId, text.strip(), TelegramClient.inlineKeyboard(rows));
    }

    private void handleWho(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        List<Subscription> subs = state.subscriptions().stream()
                .filter(s -> s.taskId().equals(task.id()) && s.active())
                .toList();
        if (subs.isEmpty()) {
            telegram.sendMessage(chatId, "На это дело пока никто не подписан.");
            return;
        }
        String body = subs.stream().map(s -> "• " + displayUser(s.chatId()) + " — " + humanSchedule(s, task)).collect(Collectors.joining("\n\n"));
        telegram.sendMessage(chatId, "Кто подписан на «" + task.title() + "»:\n\n" + body);
    }

    private void handleTimezoneSet(long chatId, String zoneText) {
        try {
            ZoneId zone = ZoneId.of(zoneText.trim());
            synchronized (this) {
                UserProfile old = synchronizedProfile(chatId);
                state.users().put(chatId, new UserProfile(old.chatId(), old.username(), old.firstName(), zone.getId(), old.alertsEnabled()));
                saveState();
            }
            telegram.sendMessage(chatId, "Таймзона обновлена: " + zone.getId());
        } catch (Exception e) {
            telegram.sendMessage(chatId, "Неизвестная таймзона. Примеры: Asia/Almaty, Europe/Moscow, Europe/Berlin.", timezoneKeyboard());
        }
    }

    private void handleUnsub(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        synchronized (this) {
            boolean removed = state.subscriptions().removeIf(s -> s.chatId() == chatId && s.taskId().equals(task.id()));
            state.prompts().removeIf(p -> p.chatId() == chatId && p.taskId().equals(task.id()));
            saveState();
            telegram.sendMessage(chatId, removed ? "Настройка удалена: " + task.title() : "У тебя не было настройки для этого дела.");
        }
    }

    private void handlePromptDone(String callbackId, String promptId, long chatId, Integer messageId) {
        synchronized (this) {
            ActivePrompt prompt = findPrompt(promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            Subscription sub = findSubscription(prompt.subscriptionId());
            TaskDefinition task = findTask(prompt.taskId());
            state.prompts().removeIf(p -> p.id().equals(promptId));
            if (sub != null && task != null) {
                replaceSubscription(advanceSubscription(sub, task, prompt.scheduledFor()));
            }
            saveState();
        }
        telegram.answerCallbackQuery(callbackId, "Отмечено как сделано");
        if (messageId != null) telegram.editMessageReplyMarkup(chatId, messageId, TelegramClient.inlineKeyboard(List.of()));
    }

    private void handlePromptSnooze(String callbackId, String promptId, int hours, long chatId) {
        synchronized (this) {
            ActivePrompt prompt = findPrompt(promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            replacePrompt(new ActivePrompt(prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(),
                    Instant.now().plus(Duration.ofHours(hours)), STATE_SNOOZED, prompt.messageId(), prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), null, false));
            saveState();
        }
        telegram.answerCallbackQuery(callbackId, "Отложено на " + hours + " ч");
        telegram.sendMessage(chatId, "Ок, напомню через " + hours + " ч.");
    }

    private void handlePromptGoDoing(String callbackId, String promptId, long chatId) {
        synchronized (this) {
            ActivePrompt prompt = findPrompt(promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            replacePrompt(new ActivePrompt(prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(),
                    Instant.now().plus(GOING_DOING_CHECK_DELAY), STATE_GOING_DOING_DELAY, prompt.messageId(), prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), null, false));
            saveState();
        }
        telegram.answerCallbackQuery(callbackId, "Хорошо");
        telegram.sendMessage(chatId, "Ок, считаю, что ты пошёл делать. Через 30 минут спрошу, сделал ли ты. Если потом молчать — буду перепингивать каждые 5 минут.");
    }

    private void sendSnoozeHoursPicker(long chatId, String promptId) {
        List<List<Map<String, Object>>> rows = List.of(
                List.of(TelegramClient.button("1 ч", "PROMPT_SN:" + promptId + ":1"), TelegramClient.button("2 ч", "PROMPT_SN:" + promptId + ":2"), TelegramClient.button("3 ч", "PROMPT_SN:" + promptId + ":3")),
                List.of(TelegramClient.button("6 ч", "PROMPT_SN:" + promptId + ":6"), TelegramClient.button("12 ч", "PROMPT_SN:" + promptId + ":12"), TelegramClient.button("24 ч", "PROMPT_SN:" + promptId + ":24"))
        );
        telegram.sendMessage(chatId, "На сколько часов отложить?", TelegramClient.inlineKeyboard(rows));
    }
    private void sendPrompt(ActivePrompt prompt, TaskDefinition task, PromptReason reason) {
        String header = switch (reason) {
            case FIRST -> "⏰ Пора заняться делом";
            case REPEAT -> "⏰ Напоминаю ещё раз";
            case SNOOZE_FINISHED -> "⏰ Время после откладывания вышло";
            case CHECK_AFTER_WORK -> "👀 Проверяю: получилось сделать?";
        };
        String when = ZonedDateTime.ofInstant(prompt.scheduledFor(), ZoneId.of(userZone(prompt.chatId()))).format(DATE_TIME_FMT);
        Integer messageId = telegram.sendMessage(prompt.chatId(), header + "\n\n" + task.title() + "\nПлан: " + when, promptKeyboard(prompt.id()));
        if (messageId != null) {
            synchronized (this) {
                replacePrompt(new ActivePrompt(prompt.id(), prompt.subscriptionId(), prompt.taskId(), prompt.chatId(), prompt.scheduledFor(), prompt.nextPingAt(), prompt.state(), messageId, prompt.alertBroadcastCount(), prompt.endOfDayAlertSent(), prompt.stageStartedAt(), prompt.currentStageAlertSent()));
                saveState();
            }
        }
    }

    private Map<String, Object> promptKeyboard(String promptId) {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("✅ Сделал", "PROMPT_DONE:" + promptId), TelegramClient.button("🏃 Пошёл делать (30 мин)", "PROMPT_GO:" + promptId)),
                List.of(TelegramClient.button("⏳ Отложить", "PROMPT_MORE:" + promptId))
        ));
    }

    private Subscription advanceSubscription(Subscription sub, TaskDefinition task, Instant previousScheduledFor) {
        if (task.kind() == TaskKind.ONE_TIME_THIS_WEEK || task.kind() == TaskKind.ONE_TIME_NEXT_WEEK) {
            return new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(), sub.dayOfWeek(), sub.dayOfMonth(), sub.zoneId(), sub.nextRunAt(), sub.active(), true);
        }
        if (task.kind() == TaskKind.MANUAL) {
            return sub;
        }
        Instant now = Instant.now();
        Instant cursor = previousScheduledFor;
        Instant next = computeImmediateNext(sub, task, cursor);
        int guard = 0;
        while (!next.isAfter(now)) {
            cursor = next;
            next = computeImmediateNext(sub, task, cursor);
            if (++guard > 2000) {
                throw new IllegalStateException("Не смог пересчитать следующий слот без догоняния");
            }
        }
        return new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(), sub.dayOfWeek(), sub.dayOfMonth(), sub.zoneId(), next, sub.active(), false);
    }

    private Instant computeImmediateNext(Subscription sub, TaskDefinition task, Instant previousScheduledFor) {
        ZoneId zone = ZoneId.of(sub.zoneId());
        return switch (task.schedule().unit()) {
            case DAY -> computeNextDaily(sub.dailyTimes(), task.schedule().interval(), zone, previousScheduledFor.plusSeconds(1));
            case WEEK -> ZonedDateTime.ofInstant(previousScheduledFor, zone).plusWeeks(task.schedule().interval()).toInstant();
            case MONTH -> nextMonthly(previousScheduledFor, zone, task.schedule().interval(), sub.dayOfMonth(), parseTime(sub.dailyTimes().getFirst()));
        };
    }

    private Instant computeNextDaily(List<String> times, int intervalDays, ZoneId zone, Instant base) {
        if (times == null || times.isEmpty()) throw new IllegalArgumentException("Для ежедневного дела нужен хотя бы один момент времени");
        List<LocalTime> localTimes = times.stream().map(this::parseTime).sorted().toList();
        ZonedDateTime now = ZonedDateTime.ofInstant(base, zone);
        for (int addDays = 0; addDays <= 370; addDays++) {
            LocalDate date = now.toLocalDate().plusDays(addDays);
            long diff = Duration.between(now.toLocalDate().atStartOfDay(zone).toInstant(), date.atStartOfDay(zone).toInstant()).toDays();
            if (diff % intervalDays != 0) continue;
            for (LocalTime lt : localTimes) {
                ZonedDateTime candidate = ZonedDateTime.of(date, lt, zone);
                if (candidate.toInstant().isAfter(base)) return candidate.toInstant();
            }
        }
        throw new IllegalStateException("Не смог посчитать nextRunAt для ежедневного дела");
    }

    private Instant nextMonthly(Instant previous, ZoneId zone, int interval, Integer desiredDay, LocalTime time) {
        ZonedDateTime moved = ZonedDateTime.ofInstant(previous, zone).plusMonths(interval);
        int day = Math.min(desiredDay == null ? moved.getDayOfMonth() : desiredDay, moved.toLocalDate().lengthOfMonth());
        return ZonedDateTime.of(LocalDate.of(moved.getYear(), moved.getMonth(), day), time, zone).toInstant();
    }

    private UserProfile registerUser(TelegramClient.Message message) {
        long chatId = message.chat().id();
        String username = message.from() != null ? message.from().username() : null;
        String firstName = message.from() != null && message.from().firstName() != null ? message.from().firstName() : "User";
        synchronized (this) {
            UserProfile existing = state.users().get(chatId);
            UserProfile profile = existing != null
                    ? new UserProfile(chatId, username, firstName, existing.zoneId(), existing.alertsEnabled())
                    : new UserProfile(chatId, username, firstName, defaultZone.getId(), true);
            state.users().put(chatId, profile);
            saveState();
            return profile;
        }
    }

    private UserProfile synchronizedProfile(long chatId) {
        UserProfile profile = state.users().get(chatId);
        if (profile == null) {
            profile = new UserProfile(chatId, null, "User", defaultZone.getId(), true);
            state.users().put(chatId, profile);
        }
        return profile;
    }

    private UserSession synchronizedSession(long chatId) {
        return state.sessions().get(chatId);
    }

    private void clearSession(long chatId) {
        UserSession session = state.sessions().remove(chatId);
        if (session != null && session.helperMessageId() != null) {
            telegram.deleteMessage(chatId, session.helperMessageId());
        }
        saveState();
    }

    private Subscription findUserSubscription(long chatId, String taskId) {
        return state.subscriptions().stream().filter(s -> s.chatId() == chatId && s.taskId().equals(taskId) && s.active()).findFirst().orElse(null);
    }

    private Subscription findSubscription(String id) {
        return state.subscriptions().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private ActivePrompt findPrompt(String id) {
        return state.prompts().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    private TaskDefinition findTask(String id) {
        return catalog.tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    private TaskDefinition resolveTask(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String value = ref.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            int idx = Integer.parseInt(value) - 1;
            return idx >= 0 && idx < catalog.tasks().size() ? catalog.tasks().get(idx) : null;
        }
        return findTask(value);
    }

    private int taskNumber(String taskId) {
        for (int i = 0; i < catalog.tasks().size(); i++) {
            if (catalog.tasks().get(i).id().equals(taskId)) return i + 1;
        }
        return -1;
    }

    private String frequencyText(TaskDefinition task) {
        if (task == null) return "—";
        return switch (task.kind()) {
            case MANUAL -> "по кнопке, без расписания";
            case ONE_TIME_THIS_WEEK -> "один раз на этой неделе";
            case ONE_TIME_NEXT_WEEK -> "один раз на следующей неделе";
            case RECURRING -> switch (task.schedule().unit()) {
                case DAY -> "каждые " + task.schedule().interval() + " дн.";
                case WEEK -> "каждые " + task.schedule().interval() + " нед.";
                case MONTH -> "каждые " + task.schedule().interval() + " мес.";
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
        return switch (task.kind()) {
            case MANUAL -> "без времени";
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK -> "один раз: " + ZonedDateTime.ofInstant(sub.nextRunAt(), zone).format(DATE_TIME_FMT);
            case RECURRING -> switch (task.schedule().unit()) {
                case DAY -> "каждые " + task.schedule().interval() + " дн. в " + String.join(", ", sub.dailyTimes()) + " (" + sub.zoneId() + ")";
                case WEEK -> "каждые " + task.schedule().interval() + " нед. по " + dayRu(sub.dayOfWeek()) + " в " + sub.dailyTimes().getFirst() + " (" + sub.zoneId() + ")";
                case MONTH -> "каждые " + task.schedule().interval() + " мес. " + sub.dayOfMonth() + " числа в " + sub.dailyTimes().getFirst() + " (" + sub.zoneId() + ")";
            };
        };
    }

    private String subscriptionsText(long chatId) {
        List<Subscription> own = state.subscriptions().stream().filter(s -> s.chatId() == chatId && s.active()).toList();
        if (own.isEmpty()) return "У тебя пока нет настроек. Открой /tasks и выбери дело.";
        StringBuilder sb = new StringBuilder("Твои настройки:\n\n");
        int i = 1;
        for (Subscription sub : own) {
            TaskDefinition task = findTask(sub.taskId());
            sb.append(i++).append(". ").append(task == null ? sub.taskId() : task.title()).append("\n")
                    .append("   ").append(task == null ? "" : humanSchedule(sub, task)).append("\n");
            if (sub.nextRunAt() != null) {
                sb.append("   Ближайший пинг: ").append(ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(sub.zoneId())).format(DATE_TIME_FMT)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private String subscriptionSummary(Subscription sub, TaskDefinition task) {
        return "✅ Настройка сохранена\n\n" + task.title() + "\n" + humanSchedule(sub, task) +
                "\nБлижайший пинг: " + ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(sub.zoneId())).format(DATE_TIME_FMT);
    }

    private void setAlertsEnabled(long chatId, boolean enabled) {
        synchronized (this) {
            UserProfile old = synchronizedProfile(chatId);
            state.users().put(chatId, new UserProfile(old.chatId(), old.username(), old.firstName(), old.zoneId(), enabled));
            saveState();
        }
    }

    private String alertsText(long chatId) {
        UserProfile profile = synchronizedProfile(chatId);
        return profile.alertsEnabled()
                ? "Ты подписан на общие алерты. Бот сообщит, если кто-то долго игнорирует дело или не закроет его до конца дня."
                : "Ты отписан от общих алертов. Бот не будет присылать тебе чужие тревожные уведомления.";
    }

    private Map<String, Object> alertsKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Получать алерты", "ALERTS_ON"), TelegramClient.button("Не получать", "ALERTS_OFF"))
        ));
    }

    private void sendGlobalAlert(ActivePrompt prompt, TaskDefinition task, AlertReason reason) {
        UserProfile owner = state.users().get(prompt.chatId());
        String who = owner == null
                ? "кто-то"
                : (owner.username() != null && !owner.username().isBlank() ? "@" + owner.username() : owner.firstName());
        String when = ZonedDateTime.ofInstant(prompt.scheduledFor(), ZoneId.of(userZone(prompt.chatId()))).format(DATE_TIME_FMT);
        String header = switch (reason) {
            case START_IGNORED -> "🚨 Алерт: игнорирует начало дела уже 30 минут";
            case CHECK_IGNORED -> "🚨 Алерт: не подтвердил завершение после кнопки «Пошёл делать»";
            case END_OF_DAY -> "🚨 Алерт: день закончился, а дело не закрыто";
        };
        String details = switch (reason) {
            case START_IGNORED -> "Человек не отреагировал на стартовый пинг. Бот уже перепингивает каждые 5 минут.";
            case CHECK_IGNORED -> "Человек нажал «Пошёл делать», но потом 30 минут игнорирует проверку «Сделал?». Бот уже перепингивает каждые 5 минут.";
            case END_OF_DAY -> "Сегодня это дело должно было быть закрыто, но подтверждения так и не было.";
        };
        String text = header + "\n\n" +
                "Дело: " + task.title() + "\n" +
                "Кто: " + who + "\n" +
                "Плановое время: " + when + "\n\n" +
                details + "\n\n" +
                "Чтобы не получать такие уведомления: /alerts off";
        for (UserProfile profile : new ArrayList<>(state.users().values())) {
            if (!profile.alertsEnabled()) continue;
            telegram.sendMessage(profile.chatId(), text, alertsKeyboard());
        }
    }

    private String timezoneText(long chatId) {
        return "Твоя текущая таймзона: " + synchronizedProfile(chatId).zoneId() + "\nМожно выбрать кнопкой ниже или прислать /tzset Europe/Berlin";
    }

    private Map<String, Object> timezoneKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Asia/Almaty", "TZ:Asia/Almaty"), TelegramClient.button("Europe/Moscow", "TZ:Europe/Moscow")),
                List.of(TelegramClient.button("Europe/Vilnius", "TZ:Europe/Vilnius"), TelegramClient.button("Europe/Berlin", "TZ:Europe/Berlin"))
        ));
    }

    private Map<String, Object> startKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Список дел", "TASK_PAGE:0"), TelegramClient.button("Таймзона", "TZ_MENU")),
                List.of(TelegramClient.button("Алерты", "ALERTS_ON"), TelegramClient.button("Без алертов", "ALERTS_OFF"))
        ));
    }

    private Map<String, Object> newTaskKindKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Каждый N день", "NEW_KIND:DAY"), TelegramClient.button("Каждую N неделю", "NEW_KIND:WEEK")),
                List.of(TelegramClient.button("Каждый N месяц", "NEW_KIND:MONTH")),
                List.of(TelegramClient.button("Разово на этой неделе", "NEW_KIND:THIS_WEEK"), TelegramClient.button("Разово на следующей", "NEW_KIND:NEXT_WEEK")),
                List.of(TelegramClient.button("Ручное дело", "NEW_KIND:MANUAL"))
        ));
    }

    private void replaceSubscription(Subscription updated) {
        state.subscriptions().removeIf(s -> s.chatId() == updated.chatId() && s.taskId().equals(updated.taskId()));
        state.subscriptions().add(updated);
    }

    private void replacePrompt(ActivePrompt updated) {
        state.prompts().removeIf(p -> p.id().equals(updated.id()));
        state.prompts().add(updated);
    }

    private void saveState() {
        stateStore.save(state);
    }

    private String startText() {
        return """
Привет. Я бот-напоминалка по делам.

Что умею:
• показывать список дел
• импортировать каталог JSON-файлом
• добавлять новые дела прямо в чате через /new
• настраивать дату и время через Mini App
• напоминать о старте дела и перепингивать каждые 5 минут, если ты не ответил
• после кнопки «Пошёл делать» перепроверять через 30 минут
• слать общие алерты другим пользователям, если дело игнорируют 30 минут

Команды:
/tasks — каталог дел
/subs — мои настройки
/new — добавить новое дело через диалог
/import — импорт каталога JSON-файлом
/tz — таймзона
/cancel — отменить текущий сценарий
/alerts — настройки общих алертов
/help — подробная справка
""";
    }

    private String helpText() {
        return """
Как пользоваться:

1) /tasks
Открой каталог дел и выбери нужное дело.

2) В карточке дела нажми «Настроить через Mini App».
Для ежедневных дел можно выбрать несколько времён.
Для недельных, месячных и разовых дел выбираются дата и точное время.

3) Когда я напомню, можно нажать:
• Сделал
• Пошёл делать
• Отложить

Если ты не нажал кнопку на стартовом пинге, я перепингую каждые 5 минут.
Если стартовый пинг игнорируют 30 минут — разошлю общий алерт всем, у кого включены алерты.
Если нажал «Пошёл делать», я перепроверю через 30 минут. Если этот контрольный пинг игнорируют — снова буду пинговать каждые 5 минут и через 30 минут такого игнора тоже разошлю алерт.
Если к концу дня дело, которое должно было быть сделано сегодня, не закрыто — тоже разошлю общий алерт.

Импорт файлом:
• Нажми /import
• Отправь .json документом
• В файле должен быть объект с полем tasks

Создание нового дела:
• Нажми /new
• Ответь на вопросы в чате

Общие алерты:
• /alerts — статус
• /alerts on — получать алерты
• /alerts off — не получать алерты
""";
    }

    private String userZone(long chatId) {
        return synchronizedProfile(chatId).zoneId();
    }

    private String displayUser(long chatId) {
        UserProfile user = synchronizedProfile(chatId);
        if (user.username() != null && !user.username().isBlank()) return "@" + user.username();
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
                DateTimeFormatter.ofPattern("HH:mm:ss")
        );
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
        if (minutes == 60) return "1 час";
        if (minutes < 60) return minutes + " мин";
        return (minutes / 60) + " ч";
    }

    private record DueAction(ActivePrompt prompt, TaskDefinition task, PromptReason reason) {}
    private record AlertAction(ActivePrompt prompt, TaskDefinition task, AlertReason reason) {}

    private enum PromptReason { FIRST, REPEAT, SNOOZE_FINISHED, CHECK_AFTER_WORK }
    private enum AlertReason { START_IGNORED, CHECK_IGNORED, END_OF_DAY }
}
