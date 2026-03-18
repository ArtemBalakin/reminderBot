package com.example.reminderbot.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TelegramClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String token;
    private final String baseUrl;
    private final String fileBaseUrl;

    public TelegramClient(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.baseUrl = "https://api.telegram.org/bot" + token + "/";
        this.fileBaseUrl = "https://api.telegram.org/file/bot" + token + "/";
    }

    public List<Update> getUpdates(long offset, int timeoutSeconds) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "offset", offset,
                "timeout", timeoutSeconds,
                "allowed_updates", List.of("message", "callback_query")
        );
        JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Update[].class);
        ApiResponse<Update[]> response = post("getUpdates", body, type);
        if (!response.ok()) {
            throw new IllegalStateException("getUpdates failed: " + response.description());
        }
        return response.result() == null ? List.of() : List.of(response.result());
    }

    public Integer sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null, false);
    }

    public Integer sendMessage(long chatId, String text, Object replyMarkup) {
        return sendMessage(chatId, text, replyMarkup, false);
    }

    public Integer sendMessage(long chatId, String text, Object replyMarkup, boolean forceReply) {
        try {
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            if (replyMarkup != null) {
                body.put("reply_markup", replyMarkup);
            } else if (forceReply) {
                body.put("reply_markup", Map.of("force_reply", true, "selective", true));
            }
            JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Message.class);
            ApiResponse<Message> response = post("sendMessage", body, type);
            if (!response.ok() || response.result() == null) {
                System.err.println("sendMessage failed: " + response.description());
                return null;
            }
            return response.result().messageId();
        } catch (Exception e) {
            System.err.println("sendMessage exception: " + e.getMessage());
            return null;
        }
    }

    public Integer sendDocument(long chatId, String filename, byte[] content, String caption) {
        try {
            String boundary = "----ReminderBot" + System.currentTimeMillis();
            List<byte[]> parts = new ArrayList<>();
            parts.add(("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n" +
                    chatId + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (caption != null && !caption.isBlank()) {
                parts.add(("--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"caption\"\r\n\r\n" +
                        caption + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            parts.add(("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"document\"; filename=\"" + filename + "\"\r\n" +
                    "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(content);
            parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
            parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "sendDocument"))
                    .timeout(Duration.ofSeconds(70))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Message.class);
            ApiResponse<Message> parsed = mapper.readValue(response.body(), type);
            return parsed.ok() && parsed.result() != null ? parsed.result().messageId() : null;
        } catch (Exception e) {
            System.err.println("sendDocument exception: " + e.getMessage());
            return null;
        }
    }

    public boolean deleteMessage(long chatId, int messageId) {
        try {
            JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Boolean.class);
            ApiResponse<Boolean> response = post("deleteMessage", Map.of("chat_id", chatId, "message_id", messageId), type);
            return response.ok();
        } catch (Exception e) {
            return false;
        }
    }

    public void editMessageReplyMarkup(long chatId, int messageId, Object replyMarkup) {
        try {
            JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Object.class);
            post("editMessageReplyMarkup", Map.of(
                    "chat_id", chatId,
                    "message_id", messageId,
                    "reply_markup", replyMarkup == null ? Map.of("inline_keyboard", List.of()) : replyMarkup
            ), type);
        } catch (Exception e) {
            System.err.println("editMessageReplyMarkup exception: " + e.getMessage());
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            Map<String, Object> body = text == null || text.isBlank()
                    ? Map.of("callback_query_id", callbackQueryId)
                    : Map.of("callback_query_id", callbackQueryId, "text", text, "show_alert", false);
            JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Object.class);
            post("answerCallbackQuery", body, type);
        } catch (Exception e) {
            System.err.println("answerCallbackQuery exception: " + e.getMessage());
        }
    }

    public String getFilePath(String fileId) throws IOException, InterruptedException {
        JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, FileInfo.class);
        ApiResponse<FileInfo> response = post("getFile", Map.of("file_id", fileId), type);
        if (!response.ok() || response.result() == null || response.result().filePath() == null) {
            throw new IllegalStateException("getFile failed: " + response.description());
        }
        return response.result().filePath();
    }

    public byte[] downloadFile(String filePath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileBaseUrl + filePath))
                .timeout(Duration.ofSeconds(70))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return response.body();
    }

    private <T> T post(String method, Object payload, JavaType type) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + method))
                .timeout(Duration.ofSeconds(70))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), type);
    }

    public static Map<String, Object> inlineKeyboard(List<List<Map<String, Object>>> rows) {
        return Map.of("inline_keyboard", rows);
    }

    public static Map<String, Object> keyboard(List<List<Map<String, Object>>> rows, boolean resize, boolean oneTime) {
        return Map.of(
                "keyboard", rows,
                "resize_keyboard", resize,
                "one_time_keyboard", oneTime
        );
    }

    public static Map<String, Object> removeKeyboard() {
        return Map.of("remove_keyboard", true);
    }

    public static Map<String, Object> button(String text, String callbackData) {
        return Map.of("text", text, "callback_data", callbackData);
    }

    public static Map<String, Object> webAppKeyboardButton(String text, String url) {
        return Map.of("text", text, "web_app", Map.of("url", url));
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse<T>(boolean ok, T result, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(long updateId, Message message, CallbackQuery callbackQuery) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Integer messageId, Chat chat, User from, String text, Document document, WebAppData webAppData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(String fileId, String fileName, String mimeType, Long fileSize) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebAppData(String data, String buttonText) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileInfo(String fileId, Integer fileSize, String filePath) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type, String username, String firstName, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, boolean isBot, String firstName, String username) {}
}
