package com.example.reminderbot.service;

import com.example.reminderbot.model.*;
import com.example.reminderbot.storage.JsonStore;
import com.example.reminderbot.telegram.TelegramClient;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

public class BotService {
    private static final DateTimeFormatter TIME_SHORT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DATE_TIME_SHORT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String STATE_WAITING = "WAITING";
    private static final String STATE_SNOOZED = "SNOOZED";
    private static final String STATE_IN_PROGRESS = "IN_PROGRESS";
    private static final Duration RE_PING_DELAY = Duration.ofMinutes(5);

    private final TelegramClient telegram;
    private final JsonStore<BotState> stateStore;
    private final JsonStore<Catalog> catalogStore;
    private final ZoneId defaultZone;

    private BotState state;
    private Catalog catalog;

    public BotService(TelegramClient telegram,
                      JsonStore<BotState> stateStore,
                      JsonStore<Catalog> catalogStore,
                      ZoneId defaultZone) {
        this.telegram = telegram;
        this.stateStore = stateStore;
        this.catalogStore = catalogStore;
        this.defaultZone = defaultZone;
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

        synchronized (this) {
            Instant now = Instant.now();
            Set<String> blockedSubscriptions = state.prompts().stream()
                    .map(ActivePrompt::subscriptionId)
                    .collect(Collectors.toSet());

            for (Subscription sub : state.subscriptions()) {
                if (!sub.active() || sub.oneTimeDone()) {
                    continue;
                }
                if (sub.nextRunAt() != null && !sub.nextRunAt().isAfter(now) && !blockedSubscriptions.contains(sub.id())) {
                    TaskDefinition task = findTask(sub.taskId());
                    if (task == null) {
                        continue;
                    }
                    ActivePrompt prompt = new ActivePrompt(
                            shortId(),
                            sub.id(),
                            sub.taskId(),
                            sub.chatId(),
                            sub.nextRunAt(),
                            now.plus(RE_PING_DELAY),
                            STATE_WAITING,
                            null
                    );
                    state.prompts().add(prompt);
                    blockedSubscriptions.add(sub.id());
                    actions.add(new DueAction(prompt, task, PromptReason.FIRST_PING));
                }
            }

            for (int i = 0; i < state.prompts().size(); i++) {
                ActivePrompt prompt = state.prompts().get(i);
                if (prompt.nextPingAt() == null || prompt.nextPingAt().isAfter(now)) {
                    continue;
                }
                TaskDefinition task = findTask(prompt.taskId());
                if (task == null) {
                    continue;
                }
                PromptReason reason;
                String nextState = STATE_WAITING;
                if (STATE_SNOOZED.equals(prompt.state())) {
                    reason = PromptReason.SNOOZE_FINISHED;
                } else if (STATE_IN_PROGRESS.equals(prompt.state())) {
                    reason = PromptReason.CHECK_AFTER_WORK;
                } else {
                    reason = PromptReason.REMINDER_AGAIN;
                }
                state.prompts().set(i, new ActivePrompt(
                        prompt.id(),
                        prompt.subscriptionId(),
                        prompt.taskId(),
                        prompt.chatId(),
                        prompt.scheduledFor(),
                        now.plus(RE_PING_DELAY),
                        nextState,
                        prompt.messageId()
                ));
                actions.add(new DueAction(state.prompts().get(i), task, reason));
            }

            saveState();
        }

        for (DueAction action : actions) {
            sendPrompt(action.prompt(), action.task(), action.reason());
        }
    }

    private void handleMessage(TelegramClient.Message message) {
        UserProfile profile = registerUser(message);
        String text = message.text() == null ? "" : message.text().trim();

        if (text.isBlank()) {
            return;
        }

        UserSession session;
        synchronized (this) {
            session = state.sessions().get(profile.chatId());
        }
        if (session != null && !text.startsWith("/") && session.type() == SessionType.IMPORT_CATALOG) {
            handleCatalogImport(profile, text);
            return;
        }

        if (text.equals("/start")) {
            telegram.sendMessage(profile.chatId(), startText(), startKeyboard());
            return;
        }
        if (text.equals("/help")) {
            telegram.sendMessage(profile.chatId(), helpText());
            return;
        }
        if (text.equals("/tasks")) {
            sendTasksPage(profile.chatId(), 0);
            return;
        }
        if (text.startsWith("/task ")) {
            handleTaskCard(profile.chatId(), text.substring(6).trim());
            return;
        }
        if (text.startsWith("/sub ")) {
            handleSubscribeEntry(profile.chatId(), text.substring(5).trim());
            return;
        }
        if (text.startsWith("/unsub ")) {
            handleUnsub(profile.chatId(), text.substring(7).trim());
            return;
        }
        if (text.equals("/subs")) {
            telegram.sendMessage(profile.chatId(), subscriptionsText(profile.chatId()));
            return;
        }
        if (text.startsWith("/who ")) {
            handleWho(profile.chatId(), text.substring(5).trim());
            return;
        }
        if (text.equals("/tz")) {
            telegram.sendMessage(profile.chatId(), timezoneText(profile.chatId()), timezoneKeyboard());
            return;
        }
        if (text.startsWith("/tzset ")) {
            handleTimezoneSet(profile.chatId(), text.substring(7).trim());
            return;
        }
        if (text.equals("/import")) {
            synchronized (this) {
                state.sessions().put(profile.chatId(), new UserSession(SessionType.IMPORT_CATALOG, null));
                saveState();
            }
            telegram.sendMessage(profile.chatId(), "Пришли следующим сообщением JSON с полем tasks. Старый каталог будет заменён.");
            return;
        }
        if (text.equals("/reload")) {
            synchronized (this) {
                this.catalog = catalogStore.load();
            }
            telegram.sendMessage(profile.chatId(), "Каталог перечитан. Всего дел: " + catalog.tasks().size());
            return;
        }
        if (text.startsWith("/ping ")) {
            handleManualPing(profile.chatId(), text.substring(6).trim());
            return;
        }

        telegram.sendMessage(profile.chatId(), "Не понял. Нажми /tasks или /help.", startKeyboard());
    }

    private void handleCallback(TelegramClient.CallbackQuery callback) {
        String data = callback.data() == null ? "" : callback.data();
        long chatId = callback.message() != null && callback.message().chat() != null
                ? callback.message().chat().id()
                : callback.from().id();

        try {
            if (data.equals("noop")) {
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("TASK_PAGE:")) {
                int page = Integer.parseInt(data.substring("TASK_PAGE:".length()));
                sendTasksPage(chatId, page);
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("TASK_SHOW:")) {
                handleTaskCard(chatId, data.substring("TASK_SHOW:".length()));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("TASK_CFG:")) {
                startSubscribeFlow(chatId, data.substring("TASK_CFG:".length()));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("SUB_START:")) {
                startSubscribeFlow(chatId, data.substring("SUB_START:".length()));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("SUB_DAILY_HOUR:")) {
                String[] parts = data.split(":");
                handleDailyHourPick(chatId, parts[1], Integer.parseInt(parts[2]));
                telegram.answerCallbackQuery(callback.id(), "Обновил часы");
                sendDailyHourPicker(chatId, findTask(parts[1]));
                return;
            }
            if (data.startsWith("SUB_DAILY_CLEAR:")) {
                clearDailySelection(chatId, data.substring("SUB_DAILY_CLEAR:".length()));
                telegram.answerCallbackQuery(callback.id(), "Очистил");
                return;
            }
            if (data.startsWith("SUB_DAILY_DONE:")) {
                handleTaskCard(chatId, data.substring("SUB_DAILY_DONE:".length()));
                telegram.answerCallbackQuery(callback.id(), "Готово");
                return;
            }
            if (data.startsWith("CALNAV:")) {
                String[] parts = data.split(":");
                sendCalendar(chatId, parts[1], YearMonth.parse(parts[2], DateTimeFormatter.ofPattern("yyyyMM")));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("DATE_PICK:")) {
                String[] parts = data.split(":");
                LocalDate date = LocalDate.parse(parts[2], DateTimeFormatter.BASIC_ISO_DATE);
                sendHourPickerForDate(chatId, parts[1], date);
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("DATE_HOUR:")) {
                String[] parts = data.split(":");
                LocalDate date = LocalDate.parse(parts[2], DateTimeFormatter.BASIC_ISO_DATE);
                int hour = Integer.parseInt(parts[3]);
                handleDatedSubscription(chatId, parts[1], date, hour);
                telegram.answerCallbackQuery(callback.id(), "Сохранил");
                return;
            }
            if (data.equals("SUBS")) {
                telegram.sendMessage(chatId, subscriptionsText(chatId));
                telegram.answerCallbackQuery(callback.id(), null);
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
            if (data.startsWith("MANUAL:")) {
                handleManualPing(chatId, data.substring(7));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("PROMPT_DONE:")) {
                handlePromptDone(callback.id(), data.substring("PROMPT_DONE:".length()), chatId,
                        callback.message() == null ? null : callback.message().messageId());
                return;
            }
            if (data.startsWith("PROMPT_MORE:")) {
                sendSnoozeHoursPicker(chatId, data.substring("PROMPT_MORE:".length()));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("PROMPT_SN:")) {
                String[] parts = data.split(":");
                handlePromptSnooze(callback.id(), parts[1], Integer.parseInt(parts[2]), chatId);
                return;
            }
            if (data.startsWith("PROMPT_GO:")) {
                sendGoDoingPicker(chatId, data.substring("PROMPT_GO:".length()));
                telegram.answerCallbackQuery(callback.id(), null);
                return;
            }
            if (data.startsWith("PROMPT_GOSET:")) {
                String[] parts = data.split(":");
                handlePromptGoDoing(callback.id(), parts[1], Integer.parseInt(parts[2]), chatId);
                return;
            }
        } catch (Exception e) {
            telegram.answerCallbackQuery(callback.id(), "Ошибка: " + e.getMessage());
            return;
        }

        telegram.answerCallbackQuery(callback.id(), "Неизвестная кнопка");
    }

    private void handleTaskCard(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        int number = taskNumber(task.id());
        long subscribers = state.subscriptions().stream()
                .filter(s -> s.taskId().equals(task.id()) && s.active())
                .count();

        Subscription own = state.subscriptions().stream()
                .filter(s -> s.chatId() == chatId && s.taskId().equals(task.id()) && s.active())
                .findFirst()
                .orElse(null);

        String ownText = own == null
                ? "У тебя это дело пока не настроено. Нажми кнопку ниже — дальше бот сам проведёт по кнопкам."
                : ("Твоя настройка:\n" + scheduleHuman(own, task));

        String text = """
                %s

                Номер: %d
                Как часто: %s
                Формат: %s
                Подписчиков: %d

                %s
                """.formatted(
                task.title(),
                number,
                frequencyText(task),
                taskKindText(task.kind()),
                subscribers,
                ownText
        );

        List<List<Map<String, String>>> rows = new ArrayList<>();
        if (task.kind() == TaskKind.MANUAL) {
            rows.add(List.of(TelegramClient.button("Пингануть сейчас", "MANUAL:" + number)));
        } else {
            rows.add(List.of(
                    TelegramClient.button(own == null ? "🗓 Настроить" : "🗓 Изменить дату и время", "TASK_CFG:" + number)
            ));
            if (own != null) {
                rows.add(List.of(TelegramClient.button("Удалить настройку", "UNSUB:" + number)));
            }
        }
        rows.add(List.of(TelegramClient.button("Кто подписан", "WHO:" + number), TelegramClient.button("К списку дел", "TASK_PAGE:0")));
        telegram.sendMessage(chatId, text, TelegramClient.inlineKeyboard(rows), false);
    }

    private void handleSubscribeEntry(long chatId, String ref) {
        startSubscribeFlow(chatId, ref);
    }

    private void startSubscribeFlow(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        if (task.kind() == TaskKind.MANUAL) {
            telegram.sendMessage(chatId, "Для этого дела нет расписания. Используй кнопку «Пингануть сейчас» или /ping <номер>.");
            return;
        }
        if (task.kind() == TaskKind.RECURRING && task.schedule().unit() == FrequencyUnit.DAY) {
            sendDailyHourPicker(chatId, task);
            return;
        }
        sendCalendar(chatId, task.id(), YearMonth.now(ZoneId.of(userZone(chatId))));
    }

    private void sendDailyHourPicker(long chatId, TaskDefinition task) {
        if (task == null) {
            telegram.sendMessage(chatId, "Дело не найдено.");
            return;
        }
        Subscription existing = findUserSubscription(chatId, task.id());
        String current = existing == null || existing.dailyTimes().isEmpty()
                ? "Пока часы не выбраны."
                : "Сейчас выбрано: " + String.join(", ", existing.dailyTimes());
        String text = """
                %s

                Выбери один или несколько часов кнопками ниже.
                Если нажать на уже выбранный час ещё раз — он уберётся.

                %s
                """.formatted(task.title(), current);
        telegram.sendMessage(chatId, text, hourKeyboard(chatId, task.id(), null, true), false);
    }

    private void sendHourPickerForDate(long chatId, String taskId, LocalDate date) {
        TaskDefinition task = findTask(taskId);
        if (task == null) {
            telegram.sendMessage(chatId, "Дело не найдено.");
            return;
        }
        String text = "Выбрана дата: " + date.format(DATE_SHORT) + "\nТеперь выбери час.";
        telegram.sendMessage(chatId, text, hourKeyboard(chatId, task.id(), date, false), false);
    }

    private void handleDailyHourPick(long chatId, String taskId, int hour) {
        TaskDefinition task = findTask(taskId);
        UserProfile profile = synchronizedProfile(chatId);
        if (task == null) {
            telegram.sendMessage(chatId, "Дело не найдено.");
            return;
        }
        LocalTime picked = LocalTime.of(hour, 0);
        ZoneId zone = ZoneId.of(profile.zoneId());
        synchronized (this) {
            Subscription existing = findUserSubscription(chatId, task.id());
            TreeSet<String> hours = new TreeSet<>();
            if (existing != null) {
                hours.addAll(existing.dailyTimes());
            }
            String formatted = picked.format(TIME_SHORT);
            if (hours.contains(formatted)) {
                hours.remove(formatted);
            } else {
                hours.add(formatted);
            }

            if (hours.isEmpty()) {
                if (existing != null) {
                    state.subscriptions().removeIf(s -> s.id().equals(existing.id()));
                }
                saveState();
                return;
            }

            Instant next = computeNextDaily(new ArrayList<>(hours), task.schedule().interval(), zone, Instant.now());
            Subscription updated = new Subscription(
                    existing == null ? shortId() : existing.id(),
                    task.id(),
                    chatId,
                    new ArrayList<>(hours),
                    null,
                    null,
                    profile.zoneId(),
                    next,
                    true,
                    false
            );
            replaceSubscription(updated);
            saveState();
        }
    }

    private void clearDailySelection(long chatId, String taskId) {
        synchronized (this) {
            state.subscriptions().removeIf(s -> s.chatId() == chatId && s.taskId().equals(taskId));
            saveState();
        }
        sendDailyHourPicker(chatId, findTask(taskId));
    }

    private void handleDatedSubscription(long chatId, String taskId, LocalDate date, int hour) {
        TaskDefinition task = findTask(taskId);
        UserProfile profile = synchronizedProfile(chatId);
        if (task == null) {
            telegram.sendMessage(chatId, "Дело не найдено.");
            return;
        }
        ZoneId zone = ZoneId.of(profile.zoneId());
        ZonedDateTime chosen = date.atTime(hour, 0).atZone(zone);
        if (!chosen.toInstant().isAfter(Instant.now())) {
            telegram.sendMessage(chatId, "Этот момент уже прошёл. Выбери более позднюю дату или час.");
            return;
        }

        Subscription updated;
        synchronized (this) {
            Subscription existing = findUserSubscription(chatId, task.id());
            updated = switch (task.kind()) {
                case RECURRING -> {
                    if (task.schedule().unit() == FrequencyUnit.WEEK) {
                        yield new Subscription(
                                existing == null ? shortId() : existing.id(),
                                task.id(),
                                chatId,
                                List.of(LocalTime.of(hour, 0).format(TIME_SHORT)),
                                date.getDayOfWeek().name(),
                                null,
                                profile.zoneId(),
                                chosen.toInstant(),
                                true,
                                false
                        );
                    }
                    yield new Subscription(
                            existing == null ? shortId() : existing.id(),
                            task.id(),
                            chatId,
                            List.of(LocalTime.of(hour, 0).format(TIME_SHORT)),
                            null,
                            date.getDayOfMonth(),
                            profile.zoneId(),
                            chosen.toInstant(),
                            true,
                            false
                    );
                }
                case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK -> new Subscription(
                        existing == null ? shortId() : existing.id(),
                        task.id(),
                        chatId,
                        List.of(LocalTime.of(hour, 0).format(TIME_SHORT)),
                        date.getDayOfWeek().name(),
                        date.getDayOfMonth(),
                        profile.zoneId(),
                        chosen.toInstant(),
                        true,
                        false
                );
                case MANUAL -> throw new IllegalArgumentException("manual");
            };
            replaceSubscription(updated);
            saveState();
        }
        telegram.sendMessage(chatId, "Готово.\n" + subscriptionCard(updated, task));
    }

    private void sendCalendar(long chatId, String taskId, YearMonth month) {
        TaskDefinition task = findTask(taskId);
        if (task == null) {
            telegram.sendMessage(chatId, "Дело не найдено.");
            return;
        }
        ZoneId zone = ZoneId.of(userZone(chatId));
        LocalDate today = ZonedDateTime.now(zone).toLocalDate();
        YearMonth normalized = normalizeCalendarMonth(task, month, today);
        String text = switch (task.kind()) {
            case RECURRING -> task.schedule().unit() == FrequencyUnit.WEEK
                    ? "Выбери первую дату. Дальше бот будет напоминать в этот же день недели."
                    : "Выбери дату. Дальше бот будет напоминать в это же число каждого цикла.";
            case ONE_TIME_THIS_WEEK -> "Выбери дату на этой неделе.";
            case ONE_TIME_NEXT_WEEK -> "Выбери дату на следующей неделе.";
            case MANUAL -> "";
        };
        telegram.sendMessage(chatId, text + "\n\n" + monthTitle(normalized), calendarKeyboard(task, normalized, today), false);
    }

    private void sendTasksPage(long chatId, int page) {
        List<TaskDefinition> tasks = catalog.tasks();
        if (tasks.isEmpty()) {
            telegram.sendMessage(chatId, "Каталог пуст. Залей JSON через /import.");
            return;
        }
        int pageSize = 5;
        int totalPages = (tasks.size() + pageSize - 1) / pageSize;
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * pageSize;
        int to = Math.min(tasks.size(), from + pageSize);

        StringBuilder sb = new StringBuilder("Выбери дело — страница ")
                .append(safePage + 1)
                .append("/")
                .append(totalPages)
                .append("\n\n");
        for (int i = from; i < to; i++) {
            TaskDefinition task = tasks.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(task.title())
                    .append("\n   ")
                    .append(frequencyText(task))
                    .append("\n\n");
        }
        sb.append("Можно открыть карточку или сразу нажать «Настроить».");

        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            int number = i + 1;
            rows.add(List.of(
                    TelegramClient.button(number + ". " + trimTitle(tasks.get(i).title()), "TASK_SHOW:" + number),
                    TelegramClient.button("🗓 Настроить", tasks.get(i).kind() == TaskKind.MANUAL ? "MANUAL:" + number : "TASK_CFG:" + number)
            ));
        }
        List<Map<String, String>> nav = new ArrayList<>();
        if (safePage > 0) nav.add(TelegramClient.button("◀️", "TASK_PAGE:" + (safePage - 1)));
        if (safePage < totalPages - 1) nav.add(TelegramClient.button("▶️", "TASK_PAGE:" + (safePage + 1)));
        if (!nav.isEmpty()) rows.add(nav);

        telegram.sendMessage(chatId, sb.toString(), TelegramClient.inlineKeyboard(rows), false);
    }
    private void handleWho(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        List<Subscription> subscriptions = state.subscriptions().stream()
                .filter(s -> s.taskId().equals(task.id()) && s.active())
                .toList();
        if (subscriptions.isEmpty()) {
            telegram.sendMessage(chatId, "На это дело пока никто не подписан.");
            return;
        }
        String body = subscriptions.stream()
                .map(this::subscriberLine)
                .collect(Collectors.joining("\n\n"));
        telegram.sendMessage(chatId, "Кто подписан на «" + task.title() + "»:\n\n" + body);
    }

    private void handleTimezoneSet(long chatId, String zoneText) {
        try {
            ZoneId zone = ZoneId.of(zoneText.trim());
            synchronized (this) {
                UserProfile old = synchronizedProfile(chatId);
                state.users().put(chatId, new UserProfile(old.chatId(), old.username(), old.firstName(), zone.getId()));
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

    private void handleCatalogImport(UserProfile profile, String text) {
        try {
            Catalog imported = catalogStore.mapper().readValue(text, Catalog.class);
            synchronized (this) {
                catalogStore.save(imported);
                this.catalog = imported;
                state.sessions().remove(profile.chatId());
                saveState();
            }
            telegram.sendMessage(profile.chatId(), "Каталог обновлён. Дел: " + imported.tasks().size());
        } catch (Exception e) {
            telegram.sendMessage(profile.chatId(), "Не смог разобрать JSON: " + e.getMessage());
        }
    }

    private void handleManualPing(long chatId, String ref) {
        TaskDefinition task = resolveTask(ref);
        if (task == null) {
            telegram.sendMessage(chatId, "Не нашёл дело: " + ref);
            return;
        }
        telegram.sendMessage(chatId, "⏰ Ручной пинг: " + task.title(), promptKeyboard("manual-" + shortId()), false);
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
        if (messageId != null) {
            telegram.editMessageReplyMarkup(chatId, messageId, TelegramClient.inlineKeyboard(List.of()));
        }
    }

    private void handlePromptSnooze(String callbackId, String promptId, int hours, long chatId) {
        synchronized (this) {
            ActivePrompt prompt = findPrompt(promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            replacePrompt(new ActivePrompt(
                    prompt.id(),
                    prompt.subscriptionId(),
                    prompt.taskId(),
                    prompt.chatId(),
                    prompt.scheduledFor(),
                    Instant.now().plus(Duration.ofHours(hours)),
                    STATE_SNOOZED,
                    prompt.messageId()
            ));
            saveState();
        }
        telegram.answerCallbackQuery(callbackId, "Отложено на " + hours + " ч");
        telegram.sendMessage(chatId, "Ок, напомню через " + hours + " ч.");
    }

    private void handlePromptGoDoing(String callbackId, String promptId, int minutes, long chatId) {
        synchronized (this) {
            ActivePrompt prompt = findPrompt(promptId);
            if (prompt == null) {
                telegram.answerCallbackQuery(callbackId, "Уже обработано");
                return;
            }
            replacePrompt(new ActivePrompt(
                    prompt.id(),
                    prompt.subscriptionId(),
                    prompt.taskId(),
                    prompt.chatId(),
                    prompt.scheduledFor(),
                    Instant.now().plus(Duration.ofMinutes(minutes)),
                    STATE_IN_PROGRESS,
                    prompt.messageId()
            ));
            saveState();
        }
        telegram.answerCallbackQuery(callbackId, "Ок");
        telegram.sendMessage(chatId, "Хорошо, перепроверю через " + minutesLabel(minutes) + ".");
    }

    private void sendSnoozeHoursPicker(long chatId, String promptId) {
        List<List<Map<String, String>>> rows = List.of(
                List.of(
                        TelegramClient.button("1 ч", "PROMPT_SN:" + promptId + ":1"),
                        TelegramClient.button("2 ч", "PROMPT_SN:" + promptId + ":2"),
                        TelegramClient.button("3 ч", "PROMPT_SN:" + promptId + ":3")
                ),
                List.of(
                        TelegramClient.button("6 ч", "PROMPT_SN:" + promptId + ":6"),
                        TelegramClient.button("12 ч", "PROMPT_SN:" + promptId + ":12"),
                        TelegramClient.button("24 ч", "PROMPT_SN:" + promptId + ":24")
                )
        );
        telegram.sendMessage(chatId, "На сколько часов отложить?", TelegramClient.inlineKeyboard(rows), false);
    }

    private void sendGoDoingPicker(long chatId, String promptId) {
        List<List<Map<String, String>>> rows = List.of(
                List.of(
                        TelegramClient.button("15 мин", "PROMPT_GOSET:" + promptId + ":15"),
                        TelegramClient.button("30 мин", "PROMPT_GOSET:" + promptId + ":30")
                ),
                List.of(
                        TelegramClient.button("1 час", "PROMPT_GOSET:" + promptId + ":60"),
                        TelegramClient.button("2 часа", "PROMPT_GOSET:" + promptId + ":120")
                )
        );
        telegram.sendMessage(chatId, "Через сколько перепроверить, всё ли получилось?", TelegramClient.inlineKeyboard(rows), false);
    }

    private void sendPrompt(ActivePrompt prompt, TaskDefinition task, PromptReason reason) {
        String body = switch (reason) {
            case FIRST_PING -> "⏰ Пора заняться делом";
            case REMINDER_AGAIN -> "⏰ Напоминаю ещё раз";
            case SNOOZE_FINISHED -> "⏰ Время после откладывания вышло";
            case CHECK_AFTER_WORK -> "👀 Проверяю: получилось сделать?";
        };
        String when = ZonedDateTime.ofInstant(prompt.scheduledFor(), ZoneId.of(userZone(prompt.chatId()))).format(DATE_TIME_SHORT);
        Integer messageId = telegram.sendMessage(prompt.chatId(), body + "\n\n" + task.title() + "\nПлан: " + when, promptKeyboard(prompt.id()), false);
        if (messageId != null) {
            synchronized (this) {
                ActivePrompt current = findPrompt(prompt.id());
                if (current != null) {
                    replacePrompt(new ActivePrompt(
                            current.id(),
                            current.subscriptionId(),
                            current.taskId(),
                            current.chatId(),
                            current.scheduledFor(),
                            current.nextPingAt(),
                            current.state(),
                            messageId
                    ));
                    saveState();
                }
            }
        }
    }

    private Map<String, Object> promptKeyboard(String promptId) {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(
                        TelegramClient.button("Сделал", "PROMPT_DONE:" + promptId),
                        TelegramClient.button("Пошёл делать", "PROMPT_GO:" + promptId)
                ),
                List.of(
                        TelegramClient.button("+1 ч", "PROMPT_SN:" + promptId + ":1"),
                        TelegramClient.button("+3 ч", "PROMPT_SN:" + promptId + ":3"),
                        TelegramClient.button("Отложить…", "PROMPT_MORE:" + promptId)
                )
        ));
    }

    private String subscriptionsText(long chatId) {
        List<Subscription> subscriptions = state.subscriptions().stream()
                .filter(s -> s.chatId() == chatId && s.active())
                .sorted(Comparator.comparing(Subscription::taskId))
                .toList();
        if (subscriptions.isEmpty()) {
            return "У тебя пока нет настроенных дел. Открой /tasks и выбери нужное.";
        }
        return subscriptions.stream()
                .map(s -> subscriptionCard(s, findTask(s.taskId())))
                .collect(Collectors.joining("\n\n", "Твои настройки:\n\n", ""));
    }

    private String subscriptionCard(Subscription sub, TaskDefinition task) {
        String next = sub.nextRunAt() == null ? "—" : ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(sub.zoneId())).format(DATE_TIME_SHORT);
        return """
                • %s
                %s
                Таймзона: %s
                Ближайший пинг: %s
                """.formatted(
                task == null ? "Удалённое дело" : task.title(),
                scheduleHuman(sub, task),
                sub.zoneId(),
                next
        );
    }

    private String subscriberLine(Subscription sub) {
        UserProfile user = state.users().get(sub.chatId());
        String who = user == null
                ? "Пользователь"
                : (user.username() != null && !user.username().isBlank() ? "@" + user.username() : user.firstName());
        TaskDefinition task = findTask(sub.taskId());
        return who + "\n" + scheduleHuman(sub, task) + "\nТаймзона: " + sub.zoneId();
    }

    private String scheduleHuman(Subscription sub, TaskDefinition task) {
        if (task == null) {
            return "—";
        }
        return switch (task.kind()) {
            case RECURRING -> switch (task.schedule().unit()) {
                case DAY -> "каждые " + intervalWord(task.schedule().interval(), "день", "дня", "дней") + " в " + String.join(", ", sub.dailyTimes());
                case WEEK -> "раз в " + intervalWord(task.schedule().interval(), "неделю", "недели", "недель") + ", по " + dowRu(sub.dayOfWeek()) + " в " + onlyTime(sub);
                case MONTH -> "раз в " + intervalWord(task.schedule().interval(), "месяц", "месяца", "месяцев") + ", " + sub.dayOfMonth() + " числа в " + onlyTime(sub);
            };
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK -> "один раз: " + ZonedDateTime.ofInstant(sub.nextRunAt(), ZoneId.of(sub.zoneId())).format(DATE_TIME_SHORT);
            case MANUAL -> "ручной запуск";
        };
    }

    private String frequencyText(TaskDefinition task) {
        if (task.kind() == TaskKind.MANUAL) return "ручной запуск";
        if (task.kind() == TaskKind.ONE_TIME_THIS_WEEK) return "один раз на этой неделе";
        if (task.kind() == TaskKind.ONE_TIME_NEXT_WEEK) return "один раз на следующей неделе";
        return switch (task.schedule().unit()) {
            case DAY -> task.schedule().interval() == 1 ? "каждый день" : "каждые " + task.schedule().interval() + " дня";
            case WEEK -> "раз в " + intervalWord(task.schedule().interval(), "неделю", "недели", "недель");
            case MONTH -> "раз в " + intervalWord(task.schedule().interval(), "месяц", "месяца", "месяцев");
        };
    }

    private String taskKindText(TaskKind kind) {
        return switch (kind) {
            case RECURRING -> "регулярное";
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK -> "разовое";
            case MANUAL -> "ручное";
        };
    }

    private String startText() {
        return """
                Это бот-напоминалка по домашним делам.

                Самый простой сценарий:
                1) Нажми «Список дел»
                2) Выбери нужное дело
                3) Нажми «🗓 Настроить»
                4) Если дело ежедневное — выбери часы кнопками
                5) Если еженедельное или ежемесячное — сначала выбери дату на календаре, потом час

                Когда придёт пинг, ты сможешь нажать:
                • Сделал
                • Пошёл делать
                • Отложить

                Если не ответишь, бот перепингует через 5 минут.
                """;
    }

    private String helpText() {
        return """
                Что умеет бот
                • показывает список дел без технических ID
                • позволяет настраивать напоминания кнопками
                • для недельных и месячных дел даёт выбрать день на календаре, потом час
                • для ежедневных дел позволяет выбрать один или несколько часов
                • если не ответить на пинг, бот напоминает снова через 5 минут
                • есть кнопка «Пошёл делать», после которой бот перепроверит позже

                Основные команды
                /tasks — список дел
                /task <номер> — карточка дела
                /subs — мои настройки
                /who <номер> — кто подписан на дело
                /tz — моя таймзона
                /tzset <таймзона> — сменить таймзону вручную
                /ping <номер> — ручной пинг для ручных дел
                /reload — перечитать catalog.json
                /import — следующим сообщением прислать новый JSON каталога

                Но вводить команды вручную почти не нужно:
                основной путь — через кнопки в /tasks.
                """;
    }

    private Map<String, Object> startKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(TelegramClient.button("Список дел", "TASK_PAGE:0")),
                List.of(TelegramClient.button("Мои настройки", "SUBS"), TelegramClient.button("Таймзона", "TZ_MENU"))
        ));
    }

    private String timezoneText(long chatId) {
        return "Твоя таймзона: " + userZone(chatId) + "\nМожно выбрать из кнопок ниже или ввести вручную: /tzset Asia/Almaty";
    }

    private Map<String, Object> timezoneKeyboard() {
        return TelegramClient.inlineKeyboard(List.of(
                List.of(
                        TelegramClient.button("Asia/Almaty", "TZ:Asia/Almaty"),
                        TelegramClient.button("Europe/Moscow", "TZ:Europe/Moscow")
                ),
                List.of(
                        TelegramClient.button("Europe/Vilnius", "TZ:Europe/Vilnius"),
                        TelegramClient.button("Europe/Berlin", "TZ:Europe/Berlin")
                ),
                List.of(TelegramClient.button("UTC", "TZ:UTC"))
        ));
    }

    private Map<String, Object> hourKeyboard(long chatId, String taskId, LocalDate date, boolean mergeForDaily) {
        ZoneId zone = ZoneId.of(userZone(chatId));
        LocalDate today = ZonedDateTime.now(zone).toLocalDate();
        int currentHour = ZonedDateTime.now(zone).getHour();
        List<List<Map<String, String>>> rows = new ArrayList<>();
        List<Map<String, String>> row = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            boolean allowed = true;
            if (date != null && date.equals(today)) {
                allowed = hour > currentHour;
            }
            String callback = allowed
                    ? (mergeForDaily
                    ? "SUB_DAILY_HOUR:" + taskId + ":" + hour
                    : "DATE_HOUR:" + taskId + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + ":" + hour)
                    : "noop";
            row.add(TelegramClient.button(String.format("%02d:00", hour), callback));
            if (row.size() == 4) {
                rows.add(new ArrayList<>(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        if (mergeForDaily) {
            rows.add(List.of(
                    TelegramClient.button("Очистить всё", "SUB_DAILY_CLEAR:" + taskId),
                    TelegramClient.button("Готово", "SUB_DAILY_DONE:" + taskId)
            ));
        } else if (date != null) {
            rows.add(List.of(TelegramClient.button("← Назад к календарю", "CALNAV:" + taskId + ":" + YearMonth.from(date).format(DateTimeFormatter.ofPattern("yyyyMM")))));
        }
        return TelegramClient.inlineKeyboard(rows);
    }

    private Map<String, Object> calendarKeyboard(TaskDefinition task, YearMonth ym, LocalDate today) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        rows.add(List.of(
                TelegramClient.button("Пн", "noop"),
                TelegramClient.button("Вт", "noop"),
                TelegramClient.button("Ср", "noop"),
                TelegramClient.button("Чт", "noop"),
                TelegramClient.button("Пт", "noop"),
                TelegramClient.button("Сб", "noop"),
                TelegramClient.button("Вс", "noop")
        ));

        LocalDate first = ym.atDay(1);
        int shift = first.getDayOfWeek().getValue() - 1;
        List<Map<String, String>> week = new ArrayList<>();
        for (int i = 0; i < shift; i++) {
            week.add(TelegramClient.button(" ", "noop"));
        }
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            boolean allowed = isDateAllowed(task, date, today);
            week.add(TelegramClient.button(String.valueOf(day), allowed
                    ? "DATE_PICK:" + task.id() + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE)
                    : "noop"));
            if (week.size() == 7) {
                rows.add(new ArrayList<>(week));
                week.clear();
            }
        }
        while (!week.isEmpty() && week.size() < 7) {
            week.add(TelegramClient.button(" ", "noop"));
        }
        if (!week.isEmpty()) {
            rows.add(week);
        }

        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);
        rows.add(List.of(
                TelegramClient.button("◀️", "CALNAV:" + task.id() + ":" + prev.format(DateTimeFormatter.ofPattern("yyyyMM"))),
                TelegramClient.button("▶️", "CALNAV:" + task.id() + ":" + next.format(DateTimeFormatter.ofPattern("yyyyMM")))
        ));

        return TelegramClient.inlineKeyboard(rows);
    }

    private boolean isDateAllowed(TaskDefinition task, LocalDate date, LocalDate today) {
        if (date.isBefore(today)) {
            return false;
        }
        return switch (task.kind()) {
            case RECURRING -> true;
            case ONE_TIME_THIS_WEEK -> sameIsoWeek(today, date);
            case ONE_TIME_NEXT_WEEK -> sameIsoWeek(today.plusWeeks(1), date);
            case MANUAL -> false;
        };
    }

    private boolean sameIsoWeek(LocalDate a, LocalDate b) {
        LocalDate aMon = a.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate bMon = b.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return aMon.equals(bMon);
    }

    private YearMonth normalizeCalendarMonth(TaskDefinition task, YearMonth requested, LocalDate today) {
        if (task.kind() == TaskKind.ONE_TIME_THIS_WEEK) {
            return YearMonth.from(today);
        }
        if (task.kind() == TaskKind.ONE_TIME_NEXT_WEEK) {
            return YearMonth.from(today.plusWeeks(1));
        }
        return requested;
    }

    private String monthTitle(YearMonth ym) {
        return ym.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, new Locale("ru")) + " " + ym.getYear();
    }

    private Subscription advanceSubscription(Subscription sub, TaskDefinition task, Instant basedOn) {
        ZoneId zone = ZoneId.of(sub.zoneId());
        Instant next = switch (task.kind()) {
            case RECURRING -> switch (task.schedule().unit()) {
                case DAY -> computeNextDailyAfter(sub.dailyTimes(), task.schedule().interval(), zone, basedOn);
                case WEEK -> computeNextWeeklyAfter(onlyTime(sub), task.schedule().interval(), zone, basedOn);
                case MONTH -> computeNextMonthlyAfter(sub.dayOfMonth(), onlyTime(sub), task.schedule().interval(), zone, basedOn);
            };
            case ONE_TIME_THIS_WEEK, ONE_TIME_NEXT_WEEK -> null;
            case MANUAL -> null;
        };
        boolean done = task.kind() == TaskKind.ONE_TIME_THIS_WEEK || task.kind() == TaskKind.ONE_TIME_NEXT_WEEK;
        return new Subscription(sub.id(), sub.taskId(), sub.chatId(), sub.dailyTimes(), sub.dayOfWeek(), sub.dayOfMonth(), sub.zoneId(), next, !done, done);
    }

    private Instant computeNextDaily(List<String> times, int intervalDays, ZoneId zone, Instant nowInstant) {
        ZonedDateTime now = ZonedDateTime.ofInstant(nowInstant, zone);
        List<LocalTime> parsed = times.stream().map(t -> LocalTime.parse(t, TIME_SHORT)).sorted().toList();
        for (LocalTime t : parsed) {
            ZonedDateTime candidate = now.toLocalDate().atTime(t).atZone(zone);
            if (candidate.isAfter(now)) {
                return candidate.toInstant();
            }
        }
        return now.toLocalDate().plusDays(intervalDays).atTime(parsed.get(0)).atZone(zone).toInstant();
    }

    private Instant computeNextDailyAfter(List<String> times, int intervalDays, ZoneId zone, Instant basedOn) {
        ZonedDateTime base = ZonedDateTime.ofInstant(basedOn, zone);
        List<LocalTime> parsed = times.stream().map(t -> LocalTime.parse(t, TIME_SHORT)).sorted().toList();
        LocalDate date = base.toLocalDate();
        LocalTime time = base.toLocalTime().withSecond(0).withNano(0);
        for (LocalTime t : parsed) {
            if (t.isAfter(time)) {
                return date.atTime(t).atZone(zone).toInstant();
            }
        }
        return date.plusDays(intervalDays).atTime(parsed.get(0)).atZone(zone).toInstant();
    }

    private Instant computeNextWeeklyAfter(String time, int intervalWeeks, ZoneId zone, Instant basedOn) {
        ZonedDateTime base = ZonedDateTime.ofInstant(basedOn, zone);
        return base.plusWeeks(intervalWeeks).with(LocalTime.parse(time, TIME_SHORT)).toInstant();
    }

    private Instant computeNextMonthlyAfter(int dayOfMonth, String time, int intervalMonths, ZoneId zone, Instant basedOn) {
        ZonedDateTime base = ZonedDateTime.ofInstant(basedOn, zone);
        YearMonth ym = YearMonth.from(base.toLocalDate().plusMonths(intervalMonths));
        int actualDay = Math.min(dayOfMonth, ym.lengthOfMonth());
        return ZonedDateTime.of(LocalDate.of(ym.getYear(), ym.getMonth(), actualDay), LocalTime.parse(time, TIME_SHORT), zone).toInstant();
    }

    private UserProfile registerUser(TelegramClient.Message message) {
        long chatId = message.chat().id();
        String username = message.from() == null ? null : message.from().username();
        String firstName = message.from() == null ? "User" : message.from().firstName();
        synchronized (this) {
            UserProfile existing = state.users().get(chatId);
            String zone = existing == null || existing.zoneId() == null || existing.zoneId().isBlank()
                    ? defaultZone.getId()
                    : existing.zoneId();
            UserProfile profile = new UserProfile(chatId, username, firstName, zone);
            state.users().put(chatId, profile);
            saveState();
            return profile;
        }
    }

    private UserProfile synchronizedProfile(long chatId) {
        synchronized (this) {
            UserProfile p = state.users().get(chatId);
            return p == null ? new UserProfile(chatId, null, "User", defaultZone.getId()) : p;
        }
    }

    private String userZone(long chatId) {
        UserProfile p = state.users().get(chatId);
        return p == null || p.zoneId() == null || p.zoneId().isBlank() ? defaultZone.getId() : p.zoneId();
    }

    private TaskDefinition resolveTask(String ref) {
        String trimmed = ref.trim();
        if (trimmed.matches("\\d+")) {
            int idx = Integer.parseInt(trimmed) - 1;
            if (idx >= 0 && idx < catalog.tasks().size()) {
                return catalog.tasks().get(idx);
            }
        }
        return catalog.tasks().stream()
                .filter(t -> t.id().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }

    private TaskDefinition findTask(String id) {
        return catalog.tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    private Subscription findUserSubscription(long chatId, String taskId) {
        return state.subscriptions().stream()
                .filter(s -> s.chatId() == chatId && s.taskId().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    private Subscription findSubscription(String id) {
        return state.subscriptions().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private ActivePrompt findPrompt(String promptId) {
        return state.prompts().stream().filter(p -> p.id().equals(promptId)).findFirst().orElse(null);
    }

    private int taskNumber(String id) {
        for (int i = 0; i < catalog.tasks().size(); i++) {
            if (catalog.tasks().get(i).id().equals(id)) {
                return i + 1;
            }
        }
        return -1;
    }

    private void replaceSubscription(Subscription sub) {
        state.subscriptions().removeIf(s -> s.id().equals(sub.id()) || (s.chatId() == sub.chatId() && s.taskId().equals(sub.taskId())));
        state.subscriptions().add(sub);
    }

    private void replacePrompt(ActivePrompt prompt) {
        state.prompts().removeIf(p -> p.id().equals(prompt.id()));
        state.prompts().add(prompt);
    }

    private void saveState() {
        stateStore.save(state);
    }

    private String onlyTime(Subscription sub) {
        return sub.dailyTimes().isEmpty() ? "—" : sub.dailyTimes().get(0);
    }

    private String currentSubscriptionShort(long chatId, String taskId) {
        Subscription sub = findUserSubscription(chatId, taskId);
        TaskDefinition task = findTask(taskId);
        return sub == null ? "—" : subscriptionCard(sub, task);
    }

    private String intervalWord(int n, String one, String twoToFour, String many) {
        if (n == 1) {
            return one;
        }
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return n + " " + twoToFour;
        }
        return n + " " + many;
    }

    private String dowRu(String english) {
        if (english == null) return "день";
        return switch (english) {
            case "MONDAY" -> "понедельникам";
            case "TUESDAY" -> "вторникам";
            case "WEDNESDAY" -> "средам";
            case "THURSDAY" -> "четвергам";
            case "FRIDAY" -> "пятницам";
            case "SATURDAY" -> "субботам";
            case "SUNDAY" -> "воскресеньям";
            default -> english;
        };
    }

    private String minutesLabel(int minutes) {
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours == 1 ? "1 час" : hours + " ч";
        }
        return minutes + " мин";
    }

    private String trimTitle(String title) {
        return title.length() <= 28 ? title : title.substring(0, 25) + "…";
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record DueAction(ActivePrompt prompt, TaskDefinition task, PromptReason reason) {}

    private enum PromptReason {
        FIRST_PING,
        REMINDER_AGAIN,
        SNOOZE_FINISHED,
        CHECK_AFTER_WORK
    }
}
