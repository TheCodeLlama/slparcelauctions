import { api } from "@/lib/api";
import type {
  AckDriftRequest,
  AdminRealtyGroupSlGroup,
  ForceUnregisterRequest,
  SlGroupRecheckResult,
} from "@/types/realty";

/**
 * Realty Groups: F — Admin SL-group moderation API client. Wraps the three
 * endpoints under
 * {@code /api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}}:
 *
 * <ul>
 *   <li>{@code POST /recheck} — synchronous reverify pass against the SL
 *       World API. Returns {@link SlGroupRecheckResult}.</li>
 *   <li>{@code POST /ack-drift} — admin acknowledges drift and the row
 *       rolls its founder snapshot forward. Returns
 *       {@link AdminRealtyGroupSlGroup} (the post-ack row).</li>
 *   <li>{@code DELETE /?force=true|false} — unregister the row. The
 *       force path bypasses the active-listings gate and cascades any
 *       in-flight case-3 listings into the bulk-suspend pipeline. Both
 *       paths require a non-blank {@code reason} in the body.</li>
 * </ul>
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 *
 * <p>Backend: {@code AdminSlGroupController}.
 */
export const realtyGroupSlGroupAdminApi = {
  recheck(
    groupPublicId: string,
    slGroupPublicId: string,
  ): Promise<SlGroupRecheckResult> {
    return api.post<SlGroupRecheckResult>(
      `/api/v1/admin/realty-groups/${groupPublicId}/sl-groups/${slGroupPublicId}/recheck`,
    );
  },

  ackDrift(
    groupPublicId: string,
    slGroupPublicId: string,
    body: AckDriftRequest = {},
  ): Promise<AdminRealtyGroupSlGroup> {
    return api.post<AdminRealtyGroupSlGroup>(
      `/api/v1/admin/realty-groups/${groupPublicId}/sl-groups/${slGroupPublicId}/ack-drift`,
      body,
    );
  },

  /**
   * Force-unregister an SL group registration. {@code force=true} bypasses
   * the active-listings gate; {@code force=false} respects it (and surfaces
   * 409 when listings would be orphaned). Reason is required on both paths.
   */
  forceUnregister(
    groupPublicId: string,
    slGroupPublicId: string,
    body: ForceUnregisterRequest,
    force = true,
  ): Promise<void> {
    const search = new URLSearchParams();
    if (force) search.set("force", "true");
    const qs = search.toString();
    return api.delete<void>(
      `/api/v1/admin/realty-groups/${groupPublicId}/sl-groups/${slGroupPublicId}${qs ? `?${qs}` : ""}`,
      { body },
    );
  },
};
