package com.example.reminderbot.miniapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public class MiniAppServer {
    private final HttpServer server;

    public MiniAppServer(int port, Executor executor) {
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mini app server", e);
        }
        this.server.setExecutor(executor);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/miniapp", this::handleMiniApp);
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, "text/plain; charset=utf-8", "ok");
    }

    private void handleMiniApp(HttpExchange exchange) throws IOException {
        Map<String, String> q = query(exchange.getRequestURI());
        respond(exchange, "text/html; charset=utf-8", html(q));
    }

    private void respond(HttpExchange exchange, String contentType, String bodyStr) throws IOException {
        byte[] body = bodyStr.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank())
            return result;
        for (String part : raw.split("&")) {
            int idx = part.indexOf('=');
            String k = idx >= 0 ? part.substring(0, idx) : part;
            String v = idx >= 0 ? part.substring(idx + 1) : "";
            result.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return result;
    }

    private String html(Map<String, String> q) {
        String taskId = js(q.getOrDefault("taskId", ""));
        String title = esc(q.getOrDefault("title", "Дело"));
        String kind = js(q.getOrDefault("kind", "RECURRING"));
        String unit = js(q.getOrDefault("unit", "DAY"));
        String interval = esc(q.getOrDefault("interval", "1"));
        String zone = esc(q.getOrDefault("zone", "UTC"));
        String slots = js(q.getOrDefault("slots", "1"));
        String timesParam = js(q.getOrDefault("times", ""));
        String dateParam = js(q.getOrDefault("date", ""));
        String timeParam = js(q.getOrDefault("time", ""));

        return "<!doctype html>\n" +
                "<html lang=\"ru\">\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\" />\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "  <title>Планировщик</title>\n" +
                "  <script src=\"https://telegram.org/js/telegram-web-app.js?61\"></script>\n" +
                "  <style>body{font-family:system-ui,Segoe UI,Arial,sans-serif;background:var(--tg-theme-bg-color,#111);color:var(--tg-theme-text-color,#fff);margin:0;padding:16px}.card{background:var(--tg-theme-secondary-bg-color,#1b1b1b);padding:16px;border-radius:16px;max-width:540px;margin:0 auto}h1{font-size:20px;margin:0 0 8px}.muted{opacity:.75;font-size:14px;margin-bottom:12px}label{display:block;font-size:14px;margin:12px 0 6px}input,button{width:100%;box-sizing:border-box;border-radius:12px;padding:12px;border:1px solid #444;background:#222;color:#fff;font-size:16px}button{background:#2f6fed;border:none;font-weight:600;margin-top:16px}.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}.times{display:flex;flex-direction:column;gap:8px;margin-top:8px}.chip{display:flex;justify-content:space-between;align-items:center;background:#222;padding:10px 12px;border-radius:12px}.ghost{background:#333}.hidden{display:none}</style>\n"
                +
                "</head>\n<body>\n<div class=\"card\">\n" +
                "<h1>Настройка: " + title + "</h1>\n" +
                "<div class=\"muted\">Тип: " + esc(q.getOrDefault("kind", "RECURRING")) + " · правило: "
                + esc(q.getOrDefault("unit", "DAY")) + " / каждые " + interval + " · зона: " + zone + "</div>\n" +
                "<div id=\"dailyBlock\" class=\"hidden\">\n<label>Время напоминаний</label>\n<div class=\"row\"><input id=\"dailyTime\" type=\"time\" step=\"60\"><button id=\"addTime\" type=\"button\" class=\"ghost\">Добавить время</button></div>\n<div id=\"times\" class=\"times\"></div>\n</div>\n"
                +
                "<div id=\"datedBlock\" class=\"hidden\">\n<label>Дата</label><input id=\"date\" type=\"date\">\n<label>Время</label><input id=\"time\" type=\"time\" step=\"60\">\n</div>\n"
                +
                "<button id=\"save\">Сохранить</button>\n</div>\n" +
                "<script>\n" +
                "const tg = window.Telegram.WebApp; tg.ready(); tg.expand();\n" +
                "const taskId = '" + taskId + "'; const kind = '" + kind + "'; const unit = '" + unit + "';\n" +
                "const minSlots = " + slots + ";\n" +
                "const dailyBlock=document.getElementById('dailyBlock'); const datedBlock=document.getElementById('datedBlock'); const timeInput=document.getElementById('dailyTime'); const list=document.getElementById('times'); const times=[];\n"
                +
                "const initTimes = '" + timesParam
                + "'; if(initTimes) initTimes.split(',').forEach(t => times.push(t));\n" +
                "const initDate = '" + dateParam + "'; const initTime = '" + timeParam + "';\n" +
                "function renderTimes(){ list.innerHTML=''; [...times].sort().forEach((t,idx)=>{ const div=document.createElement('div'); div.className='chip'; div.innerHTML='<span>'+t+'</span><button type=\"button\" class=\"ghost\" style=\"width:auto;padding:8px 12px\" data-idx=\"'+idx+'\">Удалить</button>'; list.appendChild(div);}); list.querySelectorAll('button').forEach(btn=>btn.onclick=()=>{times.splice(Number(btn.dataset.idx),1); renderTimes();}); }\n"
                +
                "function todayIso(){ return new Date().toISOString().slice(0,10); }\n" +
                "if (kind === 'RECURRING' && unit === 'DAY') { dailyBlock.classList.remove('hidden'); renderTimes(); } else { datedBlock.classList.remove('hidden'); document.getElementById('date').value = initDate || todayIso(); if(initTime) document.getElementById('time').value = initTime; }\n"
                +
                "document.getElementById('addTime').onclick=()=>{ if(!timeInput.value) return; if(!times.includes(timeInput.value)) times.push(timeInput.value); renderTimes(); timeInput.value=''; };\n"
                +
                "document.getElementById('save').onclick=()=>{ let payload; if(kind==='RECURRING' && unit==='DAY'){ if(timeInput.value && !times.includes(timeInput.value)) times.push(timeInput.value); if(times.length < minSlots){ alert('Нужно выбрать не меньше ' + minSlots + ' времён'); return; } payload={type:'subscription',taskId,mode:'daily',times:[...times].sort()}; } else { const date=document.getElementById('date').value; const time=document.getElementById('time').value; if(!date || !time){ alert('Выбери дату и время'); return; } payload={type:'subscription',taskId,mode:'dated',date,time}; } tg.sendData(JSON.stringify(payload)); tg.close(); };\n"
                +
                "</script>\n</body>\n</html>\n";
    }

    private String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String js(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }
}
