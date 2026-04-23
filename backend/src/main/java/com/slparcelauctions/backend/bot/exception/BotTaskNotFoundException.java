package com.slparcelauctions.backend.bot.exception;

import lombok.Getter;

@Getter
public class BotTaskNotFoundException extends RuntimeException {
    private final Long taskId;

    public BotTaskNotFoundException(Long taskId) {
        super("Bot task not found: " + taskId);
        this.taskId = taskId;
    }
}
