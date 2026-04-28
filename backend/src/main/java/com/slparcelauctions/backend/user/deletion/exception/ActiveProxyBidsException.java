package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

public class ActiveProxyBidsException extends RuntimeException implements DeletionPreconditionException {
    private final List<Long> proxyBidIds;

    public ActiveProxyBidsException(List<Long> proxyBidIds) {
        super("User has " + proxyBidIds.size() + " active proxy bid(s)");
        this.proxyBidIds = proxyBidIds;
    }

    @Override public String getCode() { return "ACTIVE_PROXY_BIDS"; }
    @Override public List<Long> getBlockingIds() { return proxyBidIds; }
}
