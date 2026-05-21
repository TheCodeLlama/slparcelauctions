import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AdminRealtyGroupsFilters,
  AgentCardDto,
  CreateInvitationRequest,
  CreateRealtyGroupRequest,
  InvitationDto,
  RealtyGroupPublicDto,
  RealtyGroupRowDto,
  RealtyGroupSummaryDto,
  TransferLeadershipRequest,
  UpdatePermissionsRequest,
  UpdateRealtyGroupRequest,
  UserRealtyGroupAffiliationDto,
} from "@/types/realty";

/**
 * Realty Groups API client - non-admin surface. Wraps every endpoint
 * the frontend needs from `/api/v1/realty-groups`, `/api/v1/me/...`,
 * and `/api/v1/users/{id}/realty-groups`.
 *
 * Backend emits paired logo/cover URLs (`logoLightUrl`, `logoDarkUrl`,
 * `coverLightUrl`, `coverDarkUrl`) plus `avatarUrl` as relative paths;
 * this layer does NOT transform them. Callers render via `apiUrl(...)`
 * and pick a variant via `useThemedImage` / `ThemedImage`.
 *
 * Cover + logo upload/delete take a `variant: "light" | "dark"` param
 * (plan Task 2). Each (surface, variant) is uploaded/deleted
 * independently so the admin UI can light- and dark-mode a group's
 * imagery without re-uploading the unrelated slot.
 */
export const realtyGroupsApi = {
  // ─── Group CRUD ────────────────────────────────────────────────────────
  createGroup(body: CreateRealtyGroupRequest): Promise<RealtyGroupPublicDto> {
    return api.post("/api/v1/realty-groups", body);
  },

  updateGroup(
    publicId: string,
    body: UpdateRealtyGroupRequest,
  ): Promise<RealtyGroupPublicDto> {
    return api.patch(`/api/v1/realty-groups/${publicId}`, body);
  },

  dissolveGroup(publicId: string): Promise<void> {
    return api.delete(`/api/v1/realty-groups/${publicId}`);
  },

  getGroup(publicId: string): Promise<RealtyGroupPublicDto> {
    return api.get(`/api/v1/realty-groups/${publicId}`);
  },

  getGroupBySlug(slug: string): Promise<RealtyGroupPublicDto> {
    return api.get(`/api/v1/realty-groups/by-slug/${encodeURIComponent(slug)}`);
  },

  // ─── Membership ────────────────────────────────────────────────────────
  listMembers(publicId: string): Promise<AgentCardDto[]> {
    return api.get(`/api/v1/realty-groups/${publicId}/members`);
  },

  removeMember(publicId: string, memberPublicId: string): Promise<void> {
    return api.delete(
      `/api/v1/realty-groups/${publicId}/members/${memberPublicId}`,
    );
  },

  updatePermissions(
    publicId: string,
    memberPublicId: string,
    body: UpdatePermissionsRequest,
  ): Promise<AgentCardDto> {
    return api.patch(
      `/api/v1/realty-groups/${publicId}/members/${memberPublicId}/permissions`,
      body,
    );
  },

  leave(publicId: string): Promise<void> {
    return api.post(`/api/v1/realty-groups/${publicId}/leave`);
  },

  transferLeadership(
    publicId: string,
    body: TransferLeadershipRequest,
  ): Promise<RealtyGroupPublicDto> {
    return api.post(
      `/api/v1/realty-groups/${publicId}/transfer-leadership`,
      body,
    );
  },

  // ─── Invitations (group-scoped) ────────────────────────────────────────
  invite(
    publicId: string,
    body: CreateInvitationRequest,
  ): Promise<InvitationDto> {
    return api.post(`/api/v1/realty-groups/${publicId}/invitations`, body);
  },

  listInvitations(publicId: string): Promise<InvitationDto[]> {
    return api.get(`/api/v1/realty-groups/${publicId}/invitations`);
  },

  revokeInvitation(
    publicId: string,
    invitationPublicId: string,
  ): Promise<void> {
    return api.delete(
      `/api/v1/realty-groups/${publicId}/invitations/${invitationPublicId}`,
    );
  },

  // ─── Invitations (user-scoped) ─────────────────────────────────────────
  myInvitations(): Promise<InvitationDto[]> {
    return api.get("/api/v1/me/invitations");
  },

  acceptInvitation(invitationPublicId: string): Promise<RealtyGroupSummaryDto> {
    return api.post(
      `/api/v1/me/invitations/${invitationPublicId}/accept`,
    );
  },

  declineInvitation(invitationPublicId: string): Promise<void> {
    return api.post(
      `/api/v1/me/invitations/${invitationPublicId}/decline`,
    );
  },

  // ─── User affiliations / "my groups" ───────────────────────────────────
  myGroups(): Promise<RealtyGroupSummaryDto[]> {
    return api.get("/api/v1/me/realty-groups");
  },

  userGroups(userPublicId: string): Promise<UserRealtyGroupAffiliationDto[]> {
    return api.get(`/api/v1/users/${userPublicId}/realty-groups`);
  },

  // ─── Image uploads (multipart) ─────────────────────────────────────────
  uploadLogo(
    publicId: string,
    variant: "light" | "dark",
    file: File,
  ): Promise<RealtyGroupPublicDto> {
    const fd = new FormData();
    fd.append("file", file);
    return api.post(
      `/api/v1/realty-groups/${publicId}/logo/${variant}`,
      fd,
    );
  },

  deleteLogo(
    publicId: string,
    variant: "light" | "dark",
  ): Promise<RealtyGroupPublicDto> {
    return api.delete(
      `/api/v1/realty-groups/${publicId}/logo/${variant}`,
    );
  },

  uploadCover(
    publicId: string,
    variant: "light" | "dark",
    file: File,
  ): Promise<RealtyGroupPublicDto> {
    const fd = new FormData();
    fd.append("file", file);
    return api.post(
      `/api/v1/realty-groups/${publicId}/cover/${variant}`,
      fd,
    );
  },

  deleteCover(
    publicId: string,
    variant: "light" | "dark",
  ): Promise<RealtyGroupPublicDto> {
    return api.delete(
      `/api/v1/realty-groups/${publicId}/cover/${variant}`,
    );
  },
};

/**
 * Admin-only realty groups surface. Mirrors `adminApi` shape: separate
 * export so admin pages import explicitly and don't accidentally cross
 * the auth gate from user surfaces.
 */
export const adminRealtyGroupsApi = {
  list(
    filters: AdminRealtyGroupsFilters,
  ): Promise<Page<RealtyGroupRowDto>> {
    const search = new URLSearchParams();
    search.set("status", filters.status);
    if (filters.search) search.set("search", filters.search);
    search.set("page", String(filters.page));
    search.set("size", String(filters.size));
    if (filters.sort) search.set("sort", filters.sort);
    return api.get(`/api/v1/admin/realty-groups?${search.toString()}`);
  },

  get(publicId: string): Promise<RealtyGroupPublicDto> {
    return api.get(`/api/v1/admin/realty-groups/${publicId}`);
  },

  updateAsAdmin(
    publicId: string,
    body: UpdateRealtyGroupRequest,
  ): Promise<RealtyGroupPublicDto> {
    return api.patch(`/api/v1/admin/realty-groups/${publicId}`, body);
  },

  dissolveAsAdmin(publicId: string): Promise<void> {
    return api.delete(`/api/v1/admin/realty-groups/${publicId}`);
  },

  removeMemberAsAdmin(
    publicId: string,
    memberPublicId: string,
    newLeaderPublicId?: string,
  ): Promise<void> {
    const qs = newLeaderPublicId
      ? `?newLeaderPublicId=${encodeURIComponent(newLeaderPublicId)}`
      : "";
    return api.delete(
      `/api/v1/admin/realty-groups/${publicId}/members/${memberPublicId}${qs}`,
    );
  },
};
