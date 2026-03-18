package com.example.reminderbot;

import com.example.reminderbot.miniapp.MiniAppServer;
import com.example.reminderbot.model.BotState;
import com.example.reminderbot.model.Catalog;
import com.example.reminderbot.poller.UpdatePoller;
import com.example.reminderbot.service.BotService;
import com.example.reminderbot.storage.JsonStore;
import com.example.reminderbot.telegram.TelegramClient;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        String token = requireEnv("BOT_TOKEN");
        String zone = env("BOT_ZONE", "Asia/Almaty");
        String stateFile = env("BOT_STATE_FILE", "data/state.json");
        String catalogFile = env("BOT_CATALOG_FILE", "data/catalog.json");
        String appBaseUrl = env("APP_BASE_URL", "https://example.com");
        int webPort = Integer.parseInt(env("APP_PORT", env("PORT", "8080")));

        TelegramClient telegram = new TelegramClient(token);
        JsonStore<BotState> stateStore = new JsonStore<>(Path.of(stateFile), BotState.class, BotState.empty());
        JsonStore<Catalog> catalogStore = new JsonStore<>(Path.of(catalogFile), Catalog.class, Catalog.empty());

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
        MiniAppServer miniAppServer = new MiniAppServer(webPort, executor);
        miniAppServer.start();

        BotService botService = new BotService(telegram, stateStore, catalogStore, ZoneId.of(zone), appBaseUrl);
        executor.submit(new UpdatePoller(telegram, botService));
        executor.scheduleWithFixedDelay(botService::processDueItems, 5, 30, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            miniAppServer.stop(0);
            executor.shutdownNow();
        }));

        System.out.println("Bot started. Zone=" + zone + ", miniapp port=" + webPort + ", baseUrl=" + appBaseUrl);
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing env: " + key);
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
