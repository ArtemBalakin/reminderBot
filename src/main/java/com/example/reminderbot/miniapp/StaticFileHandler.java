package com.example.reminderbot.miniapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class StaticFileHandler implements HttpHandler {
    private final Map<String, byte[]> staticCache;

    public StaticFileHandler(Map<String, byte[]> staticCache) {
        this.staticCache = staticCache;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String file = path.substring("/static/".length());
        if (file.contains("..") || file.contains("/") || file.contains("\\")) {
            respond(exchange, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] data = staticCache.get(file);
        if (data == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String contentType = file.endsWith(".css") ? "text/css; charset=utf-8"
                : file.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        respond(exchange, 200, contentType, data);
    }

    private void respond(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
