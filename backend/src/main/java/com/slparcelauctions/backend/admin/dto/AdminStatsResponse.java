package com.slparcelauctions.backend.admin.dto;

public record AdminStatsResponse(
    QueueStats queues,
    PlatformStats platform
) {
    public record QueueStats(
        long openFraudFlags,
        long pendingPayments,
        long activeDisputes
    ) {}

    public record PlatformStats(
        long activeListings,
        long totalUsers,
        long activeEscrows,
        long completedSales,
        long lindenGrossVolume,
        long lindenCommissionEarned
    ) {}
}
