package com.slparcelauctions.backend.admin.infrastructure.withdrawals.exception;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(long requested, long available) {
        super("Insufficient balance: requested L$" + requested + ", available L$" + available);
    }
}
