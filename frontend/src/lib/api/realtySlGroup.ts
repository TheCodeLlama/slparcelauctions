import { api } from "@/lib/api";
import type {
  RealtyGroupSlGroup,
  RegisterSlGroupRequest,
} from "@/types/realty";

/**
 * Realty Groups: E — SL-group registration API client. Wraps the four
 * endpoints exposed under {@code /api/v1/realty/groups/{publicId}/sl-groups}.
 *
 * <p>The list endpoint is read-by-anyone; the mutating endpoints (register,
 * unregister, recheck) require leader OR
 * {@code REGISTER_GROUP_SL_GROUPS}. The backend enforces the permission gate;
 * the frontend just lets the caller render its 403 error as needed.
 *
 * <p>Backend: {@code SlGroupController}, see
 * {@code backend/.../realty/slgroup/controller/SlGroupController.java}.
 */
export const realtySlGroupApi = {
  /**
   * List the SL-group registrations attached to the given realty group.
   * Includes both verified and pending entries; the row UI keys off
   * {@link RealtyGroupSlGroup#verified}.
   */
  list(groupPublicId: string): Promise<RealtyGroupSlGroup[]> {
    return api.get<RealtyGroupSlGroup[]>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups`,
    );
  },

  /**
   * Register a new SL group on the realty group. The backend creates the
   * row in {@code pending} state and the response carries the verification
   * code + expiry so the UI can render it immediately.
   */
  register(
    groupPublicId: string,
    body: RegisterSlGroupRequest,
  ): Promise<RealtyGroupSlGroup> {
    return api.post<RealtyGroupSlGroup>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups`,
      body,
    );
  },

  /**
   * Remove an SL-group registration. Allowed in both pending and verified
   * states; backend rejects with 409 if any active sibling listing on the
   * realty group would lose its only verified parent.
   */
  unregister(
    groupPublicId: string,
    slGroupPublicId: string,
  ): Promise<void> {
    return api.delete(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups/${slGroupPublicId}`,
    );
  },

  /**
   * Force an on-demand verification recheck for a pending registration.
   * The backend hits the SL World API immediately rather than waiting for
   * the next poller sweep. Returns the (possibly now-verified) row.
   */
  recheck(
    groupPublicId: string,
    slGroupPublicId: string,
  ): Promise<RealtyGroupSlGroup> {
    return api.post<RealtyGroupSlGroup>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups/${slGroupPublicId}/recheck`,
    );
  },
};
