package com.example.reminderbot.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class TelegramClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public TelegramClient(String token) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.baseUrl = "https://api.telegram.org/bot" + token + "/";
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
            java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
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

    public void editMessageReplyMarkup(long chatId, int messageId, Object replyMarkup) {
        try {
            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "message_id", messageId,
                    "reply_markup", replyMarkup == null ? Map.of("inline_keyboard", List.of()) : replyMarkup
            );
            JavaType type = mapper.getTypeFactory().constructParametricType(ApiResponse.class, Object.class);
            ApiResponse<Object> response = post("editMessageReplyMarkup", body, type);
            if (!response.ok()) {
                System.err.println("editMessageReplyMarkup failed: " + response.description());
            }
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
            ApiResponse<Object> response = post("answerCallbackQuery", body, type);
            if (!response.ok()) {
                System.err.println("answerCallbackQuery failed: " + response.description());
            }
        } catch (Exception e) {
            System.err.println("answerCallbackQuery exception: " + e.getMessage());
        }
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

    public static Map<String, Object> inlineKeyboard(List<List<Map<String, String>>> rows) {
        return Map.of("inline_keyboard", rows);
    }

    public static Map<String, String> button(String text, String callbackData) {
        return Map.of("text", text, "callback_data", callbackData);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse<T>(boolean ok, T result, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(long updateId, Message message, CallbackQuery callbackQuery) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Integer messageId, Chat chat, User from, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type, String username, String firstName, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, boolean isBot, String firstName, String username) {}
}
