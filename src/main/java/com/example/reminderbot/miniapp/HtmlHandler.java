package com.example.reminderbot.miniapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HtmlHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(HtmlHandler.class);
    private final Map<String, byte[]> staticCache;

    public HtmlHandler(Map<String, byte[]> staticCache) {
        this.staticCache = staticCache;
    }

    public void handleApp(HttpExchange exchange) throws IOException {
        byte[] html = staticCache.get("miniapp.html");
        if (html == null) {
            log.warn("miniapp.html не найден в кэше статики");
            respond(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", html);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Obsolete handler, redirecting to /app
        String query = exchange.getRequestURI().getRawQuery();
        String location = query == null || query.isBlank() ? "/app" : "/app?" + query;
        log.info("Обращение к устаревшему endpoint, перенаправление на {}", location);
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void respond(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
