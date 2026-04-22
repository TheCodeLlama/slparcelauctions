package com.slparcelauctions.backend.bot.exception;

import com.slparcelauctions.backend.bot.BotTaskType;

import lombok.Getter;

@Getter
public class BotTaskWrongTypeException extends RuntimeException {
    private final Long taskId;
    private final BotTaskType actual;
    private final BotTaskType expected;

    public BotTaskWrongTypeException(Long taskId, BotTaskType actual, BotTaskType expected) {
        super("Bot task " + taskId + " is of type " + actual
                + " but the endpoint expects " + expected);
        this.taskId = taskId;
        this.actual = actual;
        this.expected = expected;
    }
}
