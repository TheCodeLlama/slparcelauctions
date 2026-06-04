package com.slparcelauctions.backend.promotion.exception;

public class InvalidBoardIndexException extends RuntimeException {
    public InvalidBoardIndexException(int boardIndex, int maxBoardIndex) {
        super("Invalid board index " + boardIndex + " (valid: 1.." + maxBoardIndex + ")");
    }
}
