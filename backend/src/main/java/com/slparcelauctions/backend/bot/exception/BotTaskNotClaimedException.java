package com.slparcelauctions.backend.bot.exception;

import com.slparcelauctions.backend.bot.BotTaskStatus;

import lombok.Getter;

@Getter
public class BotTaskNotClaimedException extends RuntimeException {
    private final Long taskId;
    private final BotTaskStatus currentStatus;

    public BotTaskNotClaimedException(Long taskId, BotTaskStatus currentStatus) {
        super("Bot task " + taskId + " is not claimed (status=" + currentStatus + ")");
        this.taskId = taskId;
        this.currentStatus = currentStatus;
    }
}
