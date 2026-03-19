package com.example.reminderbot.miniapp;

import com.example.reminderbot.service.BotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;

public class MiniAppServer {
    private final HttpServer server;
    private final BotService botService;
    private final ObjectMapper mapper = new ObjectMapper();

    public MiniAppServer(int port, Executor executor, BotService botService) {
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mini app server", e);
        }
        this.botService = botService;
        this.server.setExecutor(executor);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/app", this::handleApp);
        this.server.createContext("/static/", this::handleStatic);
        this.server.createContext("/api/", this::handleApi);
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    // ── Health ──────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/plain; charset=utf-8", "ok");
    }

    // ── SPA entry point ────────────────────────────────────────────────

    private void handleApp(HttpExchange exchange) throws IOException {
        byte[] html = loadResource("miniapp/miniapp.html");
        if (html == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", html);
    }

    // ── Static files (css, js) ─────────────────────────────────────────

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String file = path.substring("/static/".length());
        // prevent path traversal
        if (file.contains("..") || file.contains("/") || file.contains("\\")) {
            respond(exchange, 403, "text/plain; charset=utf-8", "forbidden");
            return;
        }
        byte[] data = loadResource("miniapp/" + file);
        if (data == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        String contentType = file.endsWith(".css") ? "text/css; charset=utf-8"
                : file.endsWith(".js") ? "application/javascript; charset=utf-8"
                        : "application/octet-stream";
        respond(exchange, 200, contentType, data);
    }

    // ── API router ─────────────────────────────────────────────────────

    private void handleApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Map<String, String> q = query(exchange.getRequestURI());
        // chatId from query param (for debug/local) or from POST body
        long chatId = parseChatId(q.get("chatId"));

        try {
            Object result = switch (path) {
                case "/api/tasks" -> botService.apiGetTasksPage(intParam(q, "page", 0));
                case "/api/task" -> botService.apiGetTaskCard(chatId, q.getOrDefault("ref", "1"));
                case "/api/subs" -> botService.apiGetSubscriptions(chatId);
                case "/api/today" -> botService.apiGetTodayBoard();
                case "/api/board" -> botService.apiGetBoard();
                case "/api/stats" -> botService.apiGetStats();
                case "/api/settings" -> {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        JsonNode body = readJsonBody(exchange);
                        botService.apiUpdateSettings(chatId,
                                textNode(body, "zoneId"),
                                body.has("alertsEnabled") ? body.get("alertsEnabled").asBoolean() : null,
                                body.has("repingMinutes") ? body.get("repingMinutes").asInt() : null);
                        yield Map.of("ok", true);
                    }
                    yield botService.apiGetSettings(chatId);
                }
                case "/api/subscribe" -> {
                    JsonNode body = readJsonBody(exchange);
                    String mode = textNode(body, "mode");
                    List<String> times = new ArrayList<>();
                    if (body.has("times")) {
                        for (JsonNode t : body.get("times")) times.add(t.asText());
                    }
                    yield botService.apiSubscribe(chatId,
                            textNode(body, "taskRef"),
                            mode, times,
                            textNode(body, "date"),
                            textNode(body, "time"));
                }
                case "/api/unsubscribe" -> {
                    JsonNode body = readJsonBody(exchange);
                    yield botService.apiUnsubscribe(chatId, textNode(body, "taskRef"));
                }
                case "/api/task/new" -> {
                    JsonNode body = readJsonBody(exchange);
                    yield botService.apiCreateTask(
                            textNode(body, "title"),
                            textNode(body, "kindCode"),
                            textNode(body, "unit"),
                            body.has("interval") ? body.get("interval").asInt(1) : 1,
                            textNode(body, "note"));
                }
                case "/api/task/edit" -> {
                    JsonNode body = readJsonBody(exchange);
                    yield botService.apiEditTask(
                            textNode(body, "taskId"),
                            textNode(body, "property"),
                            textNode(body, "value"));
                }
                default -> Map.of("error", "Unknown API path: " + path);
            };
            respondJson(exchange, 200, result);
        } catch (Exception e) {
            respondJson(exchange, 500, Map.of("error", e.getMessage() == null ? "Internal error" : e.getMessage()));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void respond(HttpExchange exchange, int code, String contentType, String bodyStr) throws IOException {
        respond(exchange, code, contentType, bodyStr.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void respondJson(HttpExchange exchange, int code, Object data) throws IOException {
        byte[] json = mapper.writeValueAsBytes(data);
        respond(exchange, code, "application/json; charset=utf-8", json);
    }

    private JsonNode readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return mapper.readTree(is);
        }
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split("&")) {
            int idx = part.indexOf('=');
            String k = idx >= 0 ? part.substring(0, idx) : part;
            String v = idx >= 0 ? part.substring(idx + 1) : "";
            result.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return result;
    }

    private long parseChatId(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int intParam(Map<String, String> q, String key, int defaultValue) {
        String val = q.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String textNode(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText() : null;
    }

    private byte[] loadResource(String name) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            return is == null ? null : is.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
