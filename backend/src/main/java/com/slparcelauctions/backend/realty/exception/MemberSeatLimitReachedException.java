package com.slparcelauctions.backend.realty.exception;

import lombok.Getter;

@Getter
public class MemberSeatLimitReachedException extends RuntimeException {
    private final int seatLimit;
    private final long currentMemberCount;

    public MemberSeatLimitReachedException(int seatLimit, long currentMemberCount) {
        super("Realty group has reached its " + seatLimit + "-member seat limit");
        this.seatLimit = seatLimit;
        this.currentMemberCount = currentMemberCount;
    }
}
