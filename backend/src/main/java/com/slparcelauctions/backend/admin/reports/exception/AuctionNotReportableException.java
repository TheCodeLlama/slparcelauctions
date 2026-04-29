package com.slparcelauctions.backend.admin.reports.exception;

import com.slparcelauctions.backend.auction.AuctionStatus;

import lombok.Getter;

@Getter
public class AuctionNotReportableException extends RuntimeException {
    private final AuctionStatus currentStatus;

    public AuctionNotReportableException(AuctionStatus s) {
        super("Auction is " + s + ", not reportable");
        this.currentStatus = s;
    }
}
