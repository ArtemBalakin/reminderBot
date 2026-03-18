package com.example.reminderbot;

import com.example.reminderbot.miniapp.MiniAppServer;
import com.example.reminderbot.model.BotState;
import com.example.reminderbot.model.Catalog;
import com.example.reminderbot.poller.UpdatePoller;
import com.example.reminderbot.service.BotService;
import com.example.reminderbot.storage.JsonStore;
import com.example.reminderbot.storage.PostgresStore;
import com.example.reminderbot.storage.Store;
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
        Store<BotState> stateStore;
        Store<Catalog> catalogStore;

        String jdbcUrl = resolveJdbcUrl();
        if (jdbcUrl != null) {
            String dbUser = envAny("BOT_DB_USER", "PGUSER", "USERNAME");
            String dbPassword = envAny("BOT_DB_PASSWORD", "PGPASSWORD", "PASSWORD");
            String dbSchema = env("BOT_DB_SCHEMA", "public");
            stateStore = new PostgresStore<>(jdbcUrl, dbUser, dbPassword, dbSchema, "bot_state", BotState.class, BotState.empty());
            catalogStore = new PostgresStore<>(jdbcUrl, dbUser, dbPassword, dbSchema, "catalog", Catalog.class, Catalog.empty());
        } else {
            stateStore = new JsonStore<>(Path.of(stateFile), BotState.class, BotState.empty());
            catalogStore = new JsonStore<>(Path.of(catalogFile), Catalog.class, Catalog.empty());
        }

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

        System.out.println("Bot started. Zone=" + zone + ", miniapp port=" + webPort + ", baseUrl=" + appBaseUrl + (jdbcUrl != null ? ", storage=postgres" : ", storage=json"));
    }

    private static String resolveJdbcUrl() {
        String direct = envAny("BOT_DB_URL", "JDBC_POSTGRES_URI", "JDBC_DATABASE_URL");
        if (direct != null && !direct.isBlank()) return direct;
        String host = envAny("BOT_DB_HOST", "PGHOST", "HOST");
        String port = envAny("BOT_DB_PORT", "PGPORT", "PORT_DB");
        String db = envAny("BOT_DB_NAME", "PGDATABASE", "DATABASE");
        if (host != null && port != null && db != null) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }
        return null;
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

    private static String envAny(String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
