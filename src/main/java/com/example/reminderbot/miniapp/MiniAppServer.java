package com.example.reminderbot.miniapp;

import com.example.reminderbot.service.BotService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MiniAppServer {
    private final HttpServer server;
    private final Map<String, byte[]> staticCache = new HashMap<>();

    public MiniAppServer(int port, Executor executor, BotService botService) {
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mini app server", e);
        }
        for (String name : List.of("miniapp.html", "miniapp.css", "miniapp.js")) {
            byte[] data = loadResource("miniapp/" + name);
            if (data != null) staticCache.put(name, data);
        }
        this.server.setExecutor(executor);
        
        HtmlHandler htmlHandler = new HtmlHandler(staticCache);
        
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/miniapp", htmlHandler);
        this.server.createContext("/app", exchange -> htmlHandler.handleApp(exchange));
        this.server.createContext("/static/", new StaticFileHandler(staticCache));
        this.server.createContext("/api/", new ApiHandler(botService, new ObjectMapper()));
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        String resp = "ok";
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private byte[] loadResource(String name) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            return is == null ? null : is.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
