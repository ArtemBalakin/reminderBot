package com.example.reminderbot.miniapp;

import com.example.reminderbot.service.BotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiHandler implements HttpHandler {
    private final BotService botService;
    private final ObjectMapper mapper;

    public ApiHandler(BotService botService, ObjectMapper mapper) {
        this.botService = botService;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Map<String, String> q = query(exchange.getRequestURI());
        long chatId = parseChatId(q.get("chatId"));
        System.out.println("[MiniApp API] " + exchange.getRequestMethod() + " " + path + " chatId=" + chatId);

        try {
            Object result = switch (path) {
                case "/api/tasks" -> botService.apiGetTasksPage(intParam(q, "page", 0));
                case "/api/task" -> botService.apiGetTaskCard(chatId, q.getOrDefault("ref", "1"));
                case "/api/subs" -> botService.apiGetSubscriptions(chatId);
                case "/api/today" -> botService.apiGetTodayBoard();
                case "/api/board" -> botService.apiGetBoard();
                case "/api/stats" -> botService.apiGetStats();
                case "/api/calendar/eager" -> {
                    int y = intParam(q, "year", java.time.LocalDate.now().getYear());
                    int m = intParam(q, "month", java.time.LocalDate.now().getMonthValue());
                    yield botService.apiGetCalendar(y, m, q.get("zoneId"));
                }
                case "/api/calendar" -> {
                    int y = intParam(q, "year", java.time.LocalDate.now().getYear());
                    int m = intParam(q, "month", java.time.LocalDate.now().getMonthValue());
                    yield botService.apiGetCalendar(y, m, q.get("zoneId"));
                }
                case "/api/calendar/overview" -> {
                    int y = intParam(q, "year", java.time.LocalDate.now().getYear());
                    int m = intParam(q, "month", java.time.LocalDate.now().getMonthValue());
                    yield botService.apiGetCalendarOverview(y, m, q.get("zoneId"));
                }
                case "/api/calendar/day" -> {
                    String date = q.get("date");
                    int page = intParam(q, "page", 0);
                    int size = intParam(q, "size", 20);
                    yield botService.apiGetCalendarDayTasks(date, q.get("zoneId"), page, size);
                }
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
                    List<String> daysOfWeek = new ArrayList<>();
                    if (body.has("daysOfWeek")) {
                        for (JsonNode d : body.get("daysOfWeek")) daysOfWeek.add(d.asText());
                    }
                    List<Integer> daysOfMonth = new ArrayList<>();
                    if (body.has("daysOfMonth")) {
                        for (JsonNode d : body.get("daysOfMonth")) daysOfMonth.add(d.asInt());
                    }
                    yield botService.apiSubscribe(chatId,
                            textNode(body, "taskRef"),
                            mode, times,
                            textNode(body, "date"),
                            textNode(body, "time"),
                            daysOfWeek, daysOfMonth);
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
                            body.has("slots") ? body.get("slots").asInt(1) : 1,
                            textNode(body, "note"));
                }
                case "/api/task/update" -> {
                    JsonNode body = readJsonBody(exchange);
                    yield botService.apiUpdateTask(
                            textNode(body, "taskId"),
                            textNode(body, "title"),
                            textNode(body, "kindCode"),
                            body.has("interval") ? body.get("interval").asInt(1) : 1,
                            body.has("slots") ? body.get("slots").asInt(1) : 1,
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
            System.err.println("[MiniApp API] Ошибка обработки запроса " + path + ": " + e.getMessage());
            respondJson(exchange, 500, Map.of("error", e.getMessage() == null ? "Internal error" : e.getMessage()));
        }
    }

    private void respondJson(HttpExchange exchange, int code, Object data) throws IOException {
        byte[] json = mapper.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, json.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(json);
        }
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
}
