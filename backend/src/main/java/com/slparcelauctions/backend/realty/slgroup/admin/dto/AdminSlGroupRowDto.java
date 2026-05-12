package com.slparcelauctions.backend.realty.slgroup.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.dto.AdminSummaryDto;
import com.slparcelauctions.backend.realty.slgroup.SlGroupVerifyMethod;

/**
 * Admin-facing wire shape for a {@code realty_group_sl_groups} row. Surfaces the
 * full lifecycle state the moderation UI renders: registration metadata,
 * verification stamp, current founder snapshot, drift fields, and
 * unregister/admin-actor fields.
 *
 * <p>Distinct from the public {@code RealtyGroupSlGroupDto} because the public
 * surface intentionally hides drift/unregister provenance (group members see only
 * the verified status, not the moderation history).
 *
 * <p>Sub-project F spec §6.6, §13.3, §13.4.
 *
 * @param publicId                    The SL group registration row's public id.
 * @param slGroupUuid                 In-world UUID of the SL group.
 * @param slGroupName                 Cached SL group name from the most recent fetch.
 * @param verified                    Whether the registration has been founder-verified.
 * @param verifiedAt                  Timestamp of the verification event, if verified.
 * @param verifiedVia                 Verification method (only {@code FOUNDER_TERMINAL}
 *                                    post-F).
 * @param founderAvatarUuid           Founder UUID captured at verification time.
 * @param currentFounderUuid          Most recently observed founder UUID (drift snapshot).
 * @param lastRevalidatedAt           Stamp of the most recent successful revalidation.
 * @param consecutiveFetchFailures    World-API failure counter since last success.
 * @param driftDetectedAt             When drift was first observed; {@code null} when none.
 * @param driftReason                 Coded drift reason; {@code null} when none.
 * @param driftAcknowledgedAt         When the drift was acknowledged by an admin; {@code null}
 *                                    while unacknowledged.
 * @param driftAcknowledgedByAdmin    Admin who acknowledged, if any.
 * @param unregisteredAt              When the row was force-unregistered, if any.
 * @param unregisteredByAdmin         Admin who force-unregistered, if any.
 * @param unregisterReason            Coded unregister reason, if any.
 */
public record AdminSlGroupRowDto(
        UUID publicId,
        UUID slGroupUuid,
        String slGroupName,
        boolean verified,
        OffsetDateTime verifiedAt,
        SlGroupVerifyMethod verifiedVia,
        UUID founderAvatarUuid,
        UUID currentFounderUuid,
        OffsetDateTime lastRevalidatedAt,
        int consecutiveFetchFailures,
        OffsetDateTime driftDetectedAt,
        String driftReason,
        OffsetDateTime driftAcknowledgedAt,
        AdminSummaryDto driftAcknowledgedByAdmin,
        OffsetDateTime unregisteredAt,
        AdminSummaryDto unregisteredByAdmin,
        String unregisterReason
) {}
