package com.slparcelauctions.backend.support;

import lombok.Getter;

@Getter
public class SupportTicketException extends RuntimeException {

    private final SupportTicketError code;

    public SupportTicketException(SupportTicketError code) {
        super(code.name());
        this.code = code;
    }

    public SupportTicketException(SupportTicketError code, String detail) {
        super(detail);
        this.code = code;
    }
}
