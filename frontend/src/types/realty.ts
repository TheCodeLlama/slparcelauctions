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
 * Permission flag set. Backend enum at
 * `backend/.../realty/permission/RealtyGroupPermission.java` — keep this
 * in lockstep with that file. Sub-projects C/D/E/F each appended their
 * own values; Realty Groups: E added {@code CREATE_LISTING},
 * {@code MANAGE_ALL_LISTINGS}, the wallet flags, and
 * {@code REGISTER_SL_GROUP}.
 */
export type RealtyGroupPermission =
  | "INVITE_AGENTS"
  | "REMOVE_AGENTS"
  | "EDIT_GROUP_PROFILE"
  | "CONFIGURE_FEES"
  | "CREATE_LISTING"
  | "MANAGE_ALL_LISTINGS"
  | "SPEND_FROM_GROUP_WALLET"
  | "WITHDRAW_FROM_GROUP_WALLET"
  | "VIEW_GROUP_TRANSACTIONS"
  | "REGISTER_SL_GROUP";

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
  /**
   * Decimal from JSON, e.g. 0.6 for 60% of the group's agent fee. Hidden
   * (null) from anonymous viewers and non-members — same privacy gate as
   * {@link permissions} / {@link joinedAt}.
   */
  agentCommissionRate: number | null;
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
  /**
   * Decimal commission rate (0.0 .. 1.0) the invited agent will earn on
   * group listings — backend wire type is BigDecimal, accepted as number or
   * string. Optional; absence means the leader did not opt in to a custom
   * rate. {@code 0} is a legal value (group keeps 100% of earnings).
   */
  agentCommissionRate?: number;
}

export interface TransferLeadershipRequest {
  newLeaderPublicId: string;
  oldLeaderAction: OldLeaderAction;
}

export interface UpdatePermissionsRequest {
  permissions: RealtyGroupPermission[];
  /**
   * Optional commission rate update — when present it replaces the
   * member's current rate; when {@code undefined} the rate stays
   * unchanged. {@code 0} is a legal value.
   */
  agentCommissionRate?: number;
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

// ─── SL group registrations (Realty Groups: E) ─────────────────────────────

/**
 * How a {@link RealtyGroupSlGroup} reached the verified state. ABOUT_TEXT is
 * the polling-based path (founder pastes the verification code into the SL
 * group's About field, the backend's poller observes it on the next sweep);
 * FOUNDER_TERMINAL is the in-world terminal path (founder taps a registered
 * SLPA terminal to instant-verify).
 */
export type SlGroupVerifyMethod = "ABOUT_TEXT" | "FOUNDER_TERMINAL";

/**
 * Pending-state metadata for an unverified SL-group registration. Surfaced
 * to the UI so the registration row can show "code SLPA-XXXXXXXXXXXX —
 * expires in X minutes" and a Recheck Now button next to it.
 */
export interface SlGroupPending {
  /**
   * Full verification code as emitted by the backend, including the
   * {@code SLPA-} prefix (e.g. {@code "SLPA-1A2B3C4D5E6F"}). Render this
   * value directly — the prefix is part of the wire payload, not added by
   * the UI.
   */
  verificationCode: string;
  /** ISO-8601. */
  verificationCodeExpiresAt: string;
  /** ISO-8601, or null if the backend has not polled this group yet. */
  lastPolledAt: string | null;
  pollAttempts: number;
}

/**
 * One SL-group registration owned by a realty group. Wire payload from
 * {@code GET /api/v1/realty/groups/{publicId}/sl-groups} and the
 * register/recheck endpoints — see backend {@code RealtyGroupSlGroupDto}.
 *
 * {@link pending} is non-null iff {@link verified} is false. {@link verifiedAt},
 * {@link verifiedVia}, and {@link founderAvatarUuid} are non-null iff
 * {@link verified} is true.
 */
export interface RealtyGroupSlGroup {
  publicId: string;
  slGroupUuid: string;
  slGroupName: string | null;
  verified: boolean;
  verifiedAt: string | null;
  verifiedVia: SlGroupVerifyMethod | null;
  pending: SlGroupPending | null;
  founderAvatarUuid: string | null;
}

export interface RegisterSlGroupRequest {
  slGroupUuid: string;
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
