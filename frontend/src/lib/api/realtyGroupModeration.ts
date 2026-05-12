import { api } from "@/lib/api";
import type {
  IssueSuspensionRequest,
  LiftSuspensionRequest,
  RealtyGroupSuspension,
} from "@/types/realty";

/**
 * Realty Groups: F — Admin suspension/ban API client. Wraps the three
 * endpoints exposed under
 * {@code /api/v1/admin/realty-groups/{publicId}/suspensions}.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}; the {@code @PreAuthorize}
 * gate runs server-side. This module is admin-only — never imported by
 * non-admin pages.
 *
 * <p>Backend: {@code AdminRealtyGroupSuspensionController}, see
 * {@code backend/.../realty/moderation/AdminRealtyGroupSuspensionController.java}.
 */
export const realtyGroupModerationApi = {
  /**
   * List suspension history for the group (newest first). Returns both
   * active and historical rows; consumers filter on
   * {@link RealtyGroupSuspension#status}.
   */
  list(groupPublicId: string): Promise<RealtyGroupSuspension[]> {
    return api.get<RealtyGroupSuspension[]>(
      `/api/v1/admin/realty-groups/${groupPublicId}/suspensions`,
    );
  },

  /**
   * Issue a new suspension or permanent ban. A null {@code expiresAt} in
   * the request body means permanent ban; otherwise the timestamp must be
   * in the future (server enforces {@code @Future}).
   */
  issue(
    groupPublicId: string,
    body: IssueSuspensionRequest,
  ): Promise<RealtyGroupSuspension> {
    return api.post<RealtyGroupSuspension>(
      `/api/v1/admin/realty-groups/${groupPublicId}/suspensions`,
      body,
    );
  },

  /**
   * Lift an active suspension. Returns 204 No Content; the consumer
   * re-queries the list to see the updated row in LIFTED state.
   *
   * <p>Note: the request method is DELETE with a body — the backend
   * accepts a body on DELETE because Spring's
   * {@code @RequestBody @Valid} is lenient with method shape.
   */
  lift(
    groupPublicId: string,
    suspensionPublicId: string,
    body: LiftSuspensionRequest,
  ): Promise<void> {
    return api.delete<void>(
      `/api/v1/admin/realty-groups/${groupPublicId}/suspensions/${suspensionPublicId}`,
      { body },
    );
  },
};
