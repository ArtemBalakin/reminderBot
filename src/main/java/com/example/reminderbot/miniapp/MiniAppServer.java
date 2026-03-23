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
    private final Map<String, byte[]> staticCache = new HashMap<>();

    public MiniAppServer(int port, Executor executor, BotService botService) {
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mini app server", e);
        }
        this.botService = botService;
        // pre-cache static resources at startup
        for (String name : List.of("miniapp.html", "miniapp.css", "miniapp.js")) {
            byte[] data = loadResource("miniapp/" + name);
            if (data != null) staticCache.put(name, data);
        }
        this.server.setExecutor(executor);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/miniapp", this::handleMiniApp);
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

    // ── Old schedule-config page (backward compat for bot buttons) ─────

    private void handleMiniApp(HttpExchange exchange) throws IOException {
        Map<String, String> q = query(exchange.getRequestURI());
        respond(exchange, 200, "text/html; charset=utf-8", scheduleHtml(q));
    }

    private String scheduleHtml(Map<String, String> q) {
        String taskId = jsEsc(q.getOrDefault("taskId", ""));
        String title = htmlEsc(q.getOrDefault("title", "Дело"));
        String kind = jsEsc(q.getOrDefault("kind", "RECURRING"));
        String unit = jsEsc(q.getOrDefault("unit", "DAY"));
        String interval = htmlEsc(q.getOrDefault("interval", "1"));
        String zone = htmlEsc(q.getOrDefault("zone", "UTC"));
        String slots = jsEsc(q.getOrDefault("slots", "1"));
        String timesParam = jsEsc(q.getOrDefault("times", ""));
        String dateParam = jsEsc(q.getOrDefault("date", ""));
        String timeParam = jsEsc(q.getOrDefault("time", ""));
        return "<!doctype html>\n<html lang=\"ru\">\n<head>\n" +
                "  <meta charset=\"utf-8\" />\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "  <title>Планировщик</title>\n  <script src=\"https://telegram.org/js/telegram-web-app.js?61\"></script>\n" +
                "  <style>body{font-family:system-ui,Segoe UI,Arial,sans-serif;background:var(--tg-theme-bg-color,#111);color:var(--tg-theme-text-color,#fff);margin:0;padding:16px}.card{background:var(--tg-theme-secondary-bg-color,#1b1b1b);padding:16px;border-radius:16px;max-width:540px;margin:0 auto}h1{font-size:20px;margin:0 0 8px}.muted{opacity:.75;font-size:14px;margin-bottom:12px}label{display:block;font-size:14px;margin:12px 0 6px}input,button{width:100%;box-sizing:border-box;border-radius:12px;padding:12px;border:1px solid #444;background:#222;color:#fff;font-size:16px}button{background:#2f6fed;border:none;font-weight:600;margin-top:16px}.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}.times{display:flex;flex-direction:column;gap:8px;margin-top:8px}.chip{display:flex;justify-content:space-between;align-items:center;background:#222;padding:10px 12px;border-radius:12px}.ghost{background:#333}.hidden{display:none}</style>\n" +
                "</head>\n<body>\n<div class=\"card\">\n" +
                "<h1>Настройка: " + title + "</h1>\n" +
                "<div class=\"muted\">Тип: " + htmlEsc(q.getOrDefault("kind", "RECURRING")) + " · правило: " + htmlEsc(q.getOrDefault("unit", "DAY")) + " / каждые " + interval + " · зона: " + zone + "</div>\n" +
                "<div id=\"dailyBlock\" class=\"hidden\">\n<label>Время напоминаний</label>\n<div class=\"row\"><input id=\"dailyTime\" type=\"time\" step=\"60\"><button id=\"addTime\" type=\"button\" class=\"ghost\">Добавить время</button></div>\n<div id=\"times\" class=\"times\"></div>\n</div>\n" +
                "<div id=\"datedBlock\" class=\"hidden\">\n<label>Дата</label><input id=\"date\" type=\"date\">\n<label>Время</label><input id=\"time\" type=\"time\" step=\"60\">\n</div>\n" +
                "<button id=\"save\">Сохранить</button>\n</div>\n" +
                "<script>\nconst tg = window.Telegram.WebApp; tg.ready(); tg.expand();\n" +
                "const taskId = '" + taskId + "'; const kind = '" + kind + "'; const unit = '" + unit + "';\nconst minSlots = " + slots + ";\n" +
                "const dailyBlock=document.getElementById('dailyBlock'); const datedBlock=document.getElementById('datedBlock'); const timeInput=document.getElementById('dailyTime'); const list=document.getElementById('times'); const times=[];\n" +
                "const initTimes = '" + timesParam + "'; if(initTimes) initTimes.split(',').forEach(t => times.push(t));\n" +
                "const initDate = '" + dateParam + "'; const initTime = '" + timeParam + "';\n" +
                "function renderTimes(){ list.innerHTML=''; [...times].sort().forEach((t,idx)=>{ const div=document.createElement('div'); div.className='chip'; div.innerHTML='<span>'+t+'</span><button type=\"button\" class=\"ghost\" style=\"width:auto;padding:8px 12px\" data-idx=\"'+idx+'\">Удалить</button>'; list.appendChild(div);}); list.querySelectorAll('button').forEach(btn=>btn.onclick=()=>{times.splice(Number(btn.dataset.idx),1); renderTimes();}); }\n" +
                "function todayIso(){ return new Date().toISOString().slice(0,10); }\n" +
                "if (kind === 'RECURRING' && unit === 'DAY') { dailyBlock.classList.remove('hidden'); renderTimes(); } else { datedBlock.classList.remove('hidden'); document.getElementById('date').value = initDate || todayIso(); if(initTime) document.getElementById('time').value = initTime; }\n" +
                "document.getElementById('addTime').onclick=()=>{ if(!timeInput.value) return; if(!times.includes(timeInput.value)) times.push(timeInput.value); renderTimes(); timeInput.value=''; };\n" +
                "document.getElementById('save').onclick=()=>{ let payload; if(kind==='RECURRING' && unit==='DAY'){ if(timeInput.value && !times.includes(timeInput.value)) times.push(timeInput.value); if(times.length < minSlots){ alert('Нужно выбрать не меньше ' + minSlots + ' времён'); return; } payload={type:'subscription',taskId,mode:'daily',times:[...times].sort()}; } else { const date=document.getElementById('date').value; const time=document.getElementById('time').value; if(!date || !time){ alert('Выбери дату и время'); return; } payload={type:'subscription',taskId,mode:'dated',date,time}; } tg.sendData(JSON.stringify(payload)); tg.close(); };\n" +
                "</script>\n</body>\n</html>\n";
    }

    private String htmlEsc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String jsEsc(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    // ── SPA entry point ────────────────────────────────────────────────

    private void handleApp(HttpExchange exchange) throws IOException {
        byte[] html = staticCache.get("miniapp.html");
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
        if (file.contains("..") || file.contains("/") || file.contains("\\")) {
            respond(exchange, 403, "text/plain; charset=utf-8", "forbidden");
            return;
        }
        byte[] data = staticCache.get(file);
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
                case "/api/calendar" -> {
                    int y = intParam(q, "year", java.time.LocalDate.now().getYear());
                    int m = intParam(q, "month", java.time.LocalDate.now().getMonthValue());
                    yield botService.apiGetCalendar(y, m, q.get("zoneId"));
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
