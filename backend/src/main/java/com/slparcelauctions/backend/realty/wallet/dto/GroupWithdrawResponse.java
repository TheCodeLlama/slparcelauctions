package com.slparcelauctions.backend.realty.wallet.dto;

/**
 * 202 response body for POST /api/v1/realty/groups/{publicId}/wallet/withdraw.
 * Spec §5.3 / §5.6.
 */
public record GroupWithdrawResponse(long queueId, int estimatedFulfillmentSeconds) {
}
