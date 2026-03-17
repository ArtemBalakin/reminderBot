package com.example.reminderbot;

import com.example.reminderbot.model.BotState;
import com.example.reminderbot.model.Catalog;
import com.example.reminderbot.service.BotService;
import com.example.reminderbot.poller.UpdatePoller;
import com.example.reminderbot.storage.JsonStore;
import com.example.reminderbot.telegram.TelegramClient;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        String token = env("BOT_TOKEN","");
        String zone = env("BOT_ZONE", "Asia/Almaty");
        String stateFile = env("BOT_STATE_FILE", "data/state.json");
        String catalogFile = env("BOT_CATALOG_FILE", "data/catalog.json");

        TelegramClient telegram = new TelegramClient(token);
        JsonStore<BotState> stateStore = new JsonStore<>(Path.of(stateFile), BotState.class, BotState.empty());
        JsonStore<Catalog> catalogStore = new JsonStore<>(Path.of(catalogFile), Catalog.class, Catalog.empty());
        BotService botService = new BotService(telegram, stateStore, catalogStore, ZoneId.of(zone));

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        executor.submit(new UpdatePoller(telegram, botService));
        executor.scheduleWithFixedDelay(botService::processDueItems, 5, 30, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));
        System.out.println("Bot started. Zone=" + zone);
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing env: " + key);
        }
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
