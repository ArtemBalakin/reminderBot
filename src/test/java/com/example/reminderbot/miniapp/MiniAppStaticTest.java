package com.example.reminderbot.miniapp;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class MiniAppStaticTest {
    @Test
    void servesEveryLocalAssetReferencedByTheApplication() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = new MiniAppServer(0, executor, null, "test-token");
            server.start();
            try {
                var client = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:" + server.getPort();
                var html = get(client, base + "/app");
                assertEquals(200, html.statusCode());
                var source = get(client, base + "/static/miniapp.js");
                var references = Pattern.compile("(?:href|src)=\"(/static/[^\"]+)\"")
                        .matcher(html.body() + source.body());
                int checked = 0;
                while (references.find()) {
                    String path = references.group(1);
                    var asset = get(client, base + path);
                    assertEquals(200, asset.statusCode(), path);
                    assertFalse(asset.body().isEmpty(), path);
                    if (path.endsWith(".css")) assertTrue(asset.headers().firstValue("content-type").orElse("").startsWith("text/css"));
                    if (path.endsWith(".webp")) assertEquals("image/webp", asset.headers().firstValue("content-type").orElse(""));
                    checked++;
                }
                assertTrue(checked >= 7);
                assertEquals(401, get(client, base + "/api/profile").statusCode());
            } finally {
                server.stop(0);
            }
        }
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
