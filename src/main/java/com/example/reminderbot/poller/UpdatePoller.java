package com.example.reminderbot.poller;

import com.example.reminderbot.service.BotService;
import com.example.reminderbot.telegram.TelegramClient;

import java.util.List;

public class UpdatePoller implements Runnable {
    private final TelegramClient telegram;
    private final BotService botService;

    public UpdatePoller(TelegramClient telegram, BotService botService) {
        this.telegram = telegram;
        this.botService = botService;
    }

    @Override
    public void run() {
        long offset = botService.getLastUpdateId() + 1;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<TelegramClient.Update> updates = telegram.getUpdates(offset, 50);
                for (TelegramClient.Update update : updates) {
                    botService.handleUpdate(update);
                    offset = Math.max(offset, update.updateId() + 1);
                    botService.setLastUpdateId(update.updateId());
                }
            } catch (Exception e) {
                System.err.println("Polling error: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
