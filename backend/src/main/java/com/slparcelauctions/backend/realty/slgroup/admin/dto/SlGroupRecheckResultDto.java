package com.slparcelauctions.backend.realty.slgroup.admin.dto;

import java.util.UUID;

/**
 * Wire-shape response for {@code POST /api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/recheck}.
 *
 * <p>Surfaces the outcome of an on-demand revalidation pass. Mirrors the fields of
 * {@link com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyResult} so the
 * admin UI can render the same drift/no-drift state the scheduled
 * {@code SlGroupReverifyTask} would have computed.
 *
 * <p>Sub-project F spec §13.3.
 *
 * @param driftDetected      {@code true} if drift is now flagged on the row.
 * @param driftReason        Coded drift reason ({@code FOUNDER_CHANGED},
 *                           {@code GROUP_NOT_FOUND}, {@code FETCH_FAILED_REPEATEDLY})
 *                           or {@code null} when no drift is flagged.
 * @param currentFounderUuid Founder UUID observed in this fetch, or {@code null}
 *                           when the fetch failed.
 */
public record SlGroupRecheckResultDto(
        boolean driftDetected,
        String driftReason,
        UUID currentFounderUuid
) {}
