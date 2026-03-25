package com.example.reminderbot.poller;

import com.example.reminderbot.service.BotService;
import com.example.reminderbot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class UpdatePoller implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(UpdatePoller.class);
    
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
                log.error("Ошибка поллинга: {}", e.getMessage(), e);
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
