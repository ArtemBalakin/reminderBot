package com.example.reminderbot.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class TelegramModels {
    private TelegramModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse<T>(boolean ok, T result, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(long updateId, Message message, CallbackQuery callbackQuery) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(long messageId, Chat chat, User from, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type, String username, String firstName, String title) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, boolean isBot, String firstName, String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendMessageResult(long messageId) {
    }

    public record InlineKeyboardButton(String text, String callback_data) {
    }

    public record InlineKeyboardMarkup(List<List<InlineKeyboardButton>> inline_keyboard) {
    }

    public record ForceReply(boolean force_reply) {
    }
}
