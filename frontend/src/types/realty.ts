/**
 * Frontend type mirrors for the Realty Groups backend slice.
 *
 * Names match the backend DTOs verbatim (publicId, displayName, etc.) so
 * the wire payload deserializes 1:1. See:
 *
 * - `backend/.../realty/dto/RealtyGroupPublicDto.java` and friends
 * - `docs/superpowers/specs/2026-05-10-realty-groups-core-permissions-design.md`
 *
 * Image URLs (`logoUrl`, `coverUrl`, `avatarUrl`) come back as relative
 * paths emitted by the backend. Callers MUST wrap them with `apiUrl(...)`
 * at render time so the browser hits the backend rather than the page
 * origin (Amplify does not proxy `/api/*`).
 */

// ─── Enums ─────────────────────────────────────────────────────────────────

export type RealtyGroupRole = "LEADER" | "AGENT";

export type InvitationStatus =
  | "PENDING"
  | "ACCEPTED"
  | "DECLINED"
  | "REVOKED"
  | "EXPIRED";

export type OldLeaderAction = "STAY" | "LEAVE";

/**
 * The 4-value permission enum landing in this slice. Sub-projects C/D/E/F
 * each append their own values when they ship. Keep this in lockstep with
 * `backend/.../realty/permission/RealtyGroupPermission.java`.
 */
export type RealtyGroupPermission =
  | "INVITE_AGENTS"
  | "REMOVE_AGENTS"
  | "EDIT_GROUP_PROFILE"
  | "CONFIGURE_FEES";

// ─── Public / read DTOs ────────────────────────────────────────────────────

export interface LeaderCardDto {
  userPublicId: string;
  displayName: string;
  avatarUrl: string | null;
}

export interface AgentCardDto {
  memberPublicId: string;
  userPublicId: string;
  displayName: string;
  avatarUrl: string | null;
  role: RealtyGroupRole;
  /** Hidden (null) from anonymous viewers + non-members. */
  permissions: RealtyGroupPermission[] | null;
  /** Hidden (null) from anonymous viewers + non-members. */
  joinedAt: string | null;
}

export interface RealtyGroupPublicDto {
  publicId: string;
  name: string;
  slug: string;
  description: string | null;
  website: string | null;
  /** Relative path — wrap with apiUrl() at render time. */
  logoUrl: string | null;
  /** Relative path — wrap with apiUrl() at render time. */
  coverUrl: string | null;
  memberSince: string;
  leader: LeaderCardDto;
  agents: AgentCardDto[];
  agentFeeRate: string;
  agentFeeSplit: string;
  memberSeatLimit: number;
  memberCount: number;
}

export interface RealtyGroupSummaryDto {
  publicId: string;
  name: string;
  slug: string;
  logoUrl: string | null;
  memberCount: number;
  memberSince: string;
}

export interface UserRealtyGroupAffiliationDto {
  groupPublicId: string;
  groupName: string;
  groupSlug: string;
  logoUrl: string | null;
  role: RealtyGroupRole;
}

export interface InvitationDto {
  publicId: string;
  groupPublicId: string;
  groupName: string;
  groupSlug: string;
  invitedByPublicId: string;
  invitedByDisplayName: string;
  permissions: RealtyGroupPermission[];
  status: InvitationStatus;
  expiresAt: string;
  createdAt: string;
  respondedAt: string | null;
}

export interface RealtyGroupRowDto {
  publicId: string;
  name: string;
  slug: string;
  leaderPublicId: string;
  leaderDisplayName: string;
  memberCount: number;
  dissolved: boolean;
  createdAt: string;
  dissolvedAt: string | null;
}

// ─── Request bodies ────────────────────────────────────────────────────────

export interface CreateRealtyGroupRequest {
  name: string;
  description?: string;
  website?: string;
}

/**
 * Every field is optional; a missing field means "leave unchanged". On the
 * non-admin path, rename is gated by the 30-day cooldown; on the admin
 * twin it bypasses.
 */
export interface UpdateRealtyGroupRequest {
  name?: string;
  description?: string;
  website?: string;
  agentFeeRate?: string;
  agentFeeSplit?: string;
}

export interface CreateInvitationRequest {
  invitedUsername: string;
  permissions: RealtyGroupPermission[];
}

export interface TransferLeadershipRequest {
  newLeaderPublicId: string;
  oldLeaderAction: OldLeaderAction;
}

export interface UpdatePermissionsRequest {
  permissions: RealtyGroupPermission[];
}

// ─── Listing-eligible groups ───────────────────────────────────────────────

/**
 * Row in the response from {@code GET /api/v1/realty/me/listing-eligible-groups}.
 * Drives the ListAsGroupPicker on the auction-create wizard. Filtered server-side to
 * groups where the caller holds {@code CREATE_LISTING} (or is leader).
 */
export interface ListingEligibleGroup {
  publicId: string;
  name: string;
  slug: string;
  logoUrl: string | null;
  /** Decimal as number from JSON, e.g. 0.02 for a 2% rate. */
  agentFeeRate: number;
}

// ─── Group wallet ──────────────────────────────────────────────────────────

export interface LedgerActor {
  publicId: string;
  displayName: string;
}

export type GroupLedgerEntryType =
  | "LISTING_FEE_DEBIT"
  | "LISTING_FEE_REFUND"
  | "AGENT_FEE_CREDIT"
  | "WITHDRAW_QUEUED"
  | "WITHDRAW_COMPLETED"
  | "WITHDRAW_REVERSED"
  | "DORMANCY_AUTO_RETURN"
  | "ADJUSTMENT";

export interface GroupLedgerEntry {
  publicId: string;
  entryType: GroupLedgerEntryType;
  amount: number;
  balanceAfter: number;
  reservedAfter: number;
  refType?: string;
  refPublicId?: string;
  actor?: LedgerActor;
  createdAt: string;
}

export interface GroupWallet {
  balance: number;
  reserved: number;
  available: number;
  recentLedger: GroupLedgerEntry[];
}

export interface GroupWithdrawRequest {
  amount: number;
  idempotencyKey: string;
}

export interface GroupWithdrawResponse {
  queueId: number;
  estimatedFulfillmentSeconds: number;
}

// ─── Admin list filters ────────────────────────────────────────────────────

export type AdminRealtyGroupsStatusFilter = "active" | "dissolved" | "all";

export interface AdminRealtyGroupsFilters {
  status: AdminRealtyGroupsStatusFilter;
  search?: string;
  page: number;
  size: number;
  /** `property,direction[;...]` — same format the admin listings table uses. */
  sort?: string;
}
