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
 *
 * {@code agentCommissionRate} is the calling user's per-member commission rate
 * within the group, projected from {@code realty_group_members.agent_commission_rate}
 * (sub-project G section 6.2). The wizard reads it directly off the eligible-list row
 * for the case-3 fee preview, avoiding a second round-trip via {@code useRealtyGroup}.
 */
export interface ListingEligibleGroup {
  publicId: string;
  name: string;
  slug: string;
  logoUrl: string | null;
  /** Decimal as number from JSON, e.g. 0.10 for a 10% per-member commission rate. */
  agentCommissionRate: number;
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
  /**
   * ISO-8601 timestamp the group leader accepted wallet Terms of Service, or
   * null if they have not. Sub-project G §7.5 — drives the
   * {@code LeaderTermsBlockBanner} render condition. Sourced server-side from
   * {@code User.walletTermsAcceptedAt} on the group's current leader row.
   */
  leaderTermsAcceptedAt: string | null;
  recentLedger: GroupLedgerEntry[];
}

/**
 * Sub-project G §7.3 — group wallet withdrawal destination.
 * `AVATAR` routes L$ to the group leader's verified SL avatar (pre-G flow).
 * `SL_GROUP` routes to the currently-registered SL group for the realty group
 * (bot-fulfilled via Self.GiveGroupMoney).
 */
export type GroupWithdrawRecipient = "AVATAR" | "SL_GROUP";

export interface GroupWithdrawRequest {
  amount: number;
  idempotencyKey: string;
  recipient: GroupWithdrawRecipient;
}

export interface GroupWithdrawResponse {
  queueId: number;
  estimatedFulfillmentSeconds: number;
}

// ─── SL group registrations (Realty Groups: E) ─────────────────────────────

/**
 * How a {@link RealtyGroupSlGroup} reached the verified state.
 * FOUNDER_TERMINAL is the in-world terminal path (founder taps a registered
 * SLPA terminal to instant-verify).
 *
 * Kept as a single-value union for forward-compatibility with future
 * verification methods.
 */
export type SlGroupVerifyMethod = "FOUNDER_TERMINAL";

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

// ─── Realty Groups: F — Admin moderation ───────────────────────────────────

/**
 * Compact admin summary embedded in admin-facing DTOs (suspension records,
 * report rows, drift-ack rows). Mirrors the backend
 * {@code com.slparcelauctions.backend.admin.dto.AdminSummaryDto} record.
 */
export interface AdminSummaryDto {
  publicId: string;
  displayName: string;
}

/**
 * Why an admin issued a {@link RealtyGroupSuspension}. Backend enum at
 * {@code com.slparcelauctions.backend.realty.moderation.SuspensionReason} —
 * informational only; suspension lifecycle is driven by {@code expiresAt}.
 */
export type SuspensionReason =
  | "FRAUD"
  | "REPORTS_RESOLVED_AGAINST"
  | "TOS_VIOLATION"
  | "ABUSE"
  | "OTHER";

/**
 * Lifecycle status of a {@link RealtyGroupSuspension}. Computed by the backend
 * from the row's {@code issuedAt} / {@code expiresAt} / {@code liftedAt}
 * timestamps; rendered as a status pill in the admin UI.
 *
 * - {@code ACTIVE_TIMED}     — not lifted, has expiry in the future.
 * - {@code ACTIVE_PERMANENT} — not lifted, no expiry (permanent ban).
 * - {@code LIFTED}           — {@code liftedAt} is set.
 * - {@code EXPIRED}          — terminal state: expiry passed but the sweep
 *                              task has not yet stamped {@code liftedAt}.
 */
export type SuspensionStatus =
  | "ACTIVE_TIMED"
  | "ACTIVE_PERMANENT"
  | "LIFTED"
  | "EXPIRED";

/**
 * Wire shape from the suspension list/issue/lift endpoints. Backend record
 * {@code com.slparcelauctions.backend.realty.moderation.dto.SuspensionDto}.
 *
 * <p>{@code expiresAt} is null for permanent bans; {@code liftedAt} +
 * {@code liftedByAdmin} + {@code liftedNotes} are non-null iff the row has
 * been lifted (manually or by the expiry sweep).
 */
export interface RealtyGroupSuspension {
  publicId: string;
  reason: SuspensionReason;
  notes: string;
  issuedAt: string;
  expiresAt: string | null;
  liftedAt: string | null;
  liftedNotes: string | null;
  issuedByAdmin: AdminSummaryDto;
  liftedByAdmin: AdminSummaryDto | null;
  status: SuspensionStatus;
}

/**
 * Request body for {@code POST /api/v1/admin/realty-groups/{publicId}/suspensions}.
 * A null {@code expiresAt} is a permanent ban; otherwise must be in the future.
 * {@code bulkSuspendListings} opts into the cascading bulk-listing-suspend flow.
 */
export interface IssueSuspensionRequest {
  reason: SuspensionReason;
  notes: string;
  /** ISO-8601; omit / null for permanent ban. */
  expiresAt?: string | null;
  bulkSuspendListings: boolean;
}

/**
 * Request body for
 * {@code DELETE /api/v1/admin/realty-groups/{publicId}/suspensions/{suspensionPublicId}}.
 *
 * <p>{@code notes} is optional; {@code bulkReinstateListings} opts into the
 * cascading bulk-listing-reinstate flow.
 */
export interface LiftSuspensionRequest {
  notes?: string;
  bulkReinstateListings: boolean;
}

/**
 * Why a user submitted a {@link RealtyGroupReport}. Backend enum at
 * {@code com.slparcelauctions.backend.realty.reports.RealtyGroupReportReason}.
 */
export type RealtyGroupReportReason =
  | "FRAUDULENT_LISTINGS"
  | "MISLEADING_ATTRIBUTION"
  | "HARASSMENT"
  | "IMPERSONATION"
  | "SPAM"
  | "OTHER";

/** Lifecycle status of a {@link RealtyGroupReport}. */
export type RealtyGroupReportStatus = "OPEN" | "RESOLVED" | "DISMISSED";

/**
 * Reporter-facing wire shape from {@code POST /api/v1/realty-groups/{publicId}/reports}
 * (status 201). Backend record
 * {@code com.slparcelauctions.backend.realty.reports.dto.ReportDto}.
 *
 * <p>Narrower than the admin row — reporters see existence + status, not the
 * resolution fields.
 */
export interface RealtyGroupReport {
  publicId: string;
  groupPublicId: string;
  reason: RealtyGroupReportReason;
  status: RealtyGroupReportStatus;
  createdAt: string;
}

/** Request body for {@code POST /api/v1/realty-groups/{publicId}/reports}. */
export interface SubmitReportRequest {
  reason: RealtyGroupReportReason;
  /** 10–2000 chars; backend validates via {@code @Size(min=10, max=2000)}. */
  details: string;
}

/**
 * Compact wire-shape for a single row in the admin realty-group reports queue.
 * Backend record
 * {@code com.slparcelauctions.backend.realty.reports.dto.AdminReportRowDto}.
 */
export interface AdminRealtyGroupReportRow {
  publicId: string;
  groupPublicId: string;
  groupName: string;
  reporter: AdminSummaryDto;
  reason: RealtyGroupReportReason;
  status: RealtyGroupReportStatus;
  createdAt: string;
}

/**
 * Full report detail returned by the admin detail / resolve / dismiss endpoints.
 * Backend record
 * {@code com.slparcelauctions.backend.realty.reports.dto.AdminReportDetailDto}.
 */
export interface AdminRealtyGroupReportDetail {
  publicId: string;
  group: {
    publicId: string;
    name: string;
  };
  reporter: AdminSummaryDto;
  reason: RealtyGroupReportReason;
  details: string;
  status: RealtyGroupReportStatus;
  resolvedByAdmin: AdminSummaryDto | null;
  resolvedAt: string | null;
  resolutionNotes: string | null;
  createdAt: string;
}

/**
 * Request body for {@code POST /api/v1/admin/realty-groups/reports/{publicId}/resolve}.
 *
 * <p>{@code escalateTo} is informational only — the backend never acts on it.
 * The frontend uses it to decide whether to chain into a suspension modal
 * after resolve returns.
 */
export interface AdminResolveReportRequest {
  notes: string;
  escalateTo?: "SUSPEND_GROUP" | "BAN_GROUP" | null;
}

/** Request body for {@code POST /api/v1/admin/realty-groups/reports/{publicId}/dismiss}. */
export interface AdminDismissReportRequest {
  notes: string;
}

/** Filters threaded through the admin reports queue list query. */
export interface AdminRealtyGroupReportsFilters {
  status?: RealtyGroupReportStatus;
  page: number;
  size: number;
  /** `property,direction[;...]` — Spring sort format. */
  sort?: string;
}

/**
 * Response body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/listings/suspend-all}.
 * Backend record
 * {@code com.slparcelauctions.backend.realty.moderation.dto.BulkSuspendResultDto}.
 */
export interface BulkSuspendResult {
  bulkActionId: string;
  suspendedCount: number;
}

/**
 * Request body for the bulk suspend cascade. {@code groupSuspensionPublicId}
 * links the cascade back to an existing suspension row (optional).
 */
export interface BulkSuspendListingsRequest {
  reason: string;
  notes?: string;
  groupSuspensionPublicId?: string | null;
}

/**
 * Response body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/listings/reinstate-all}.
 */
export interface BulkReinstateResult {
  reinstatedCount: number;
}

/** Request body for the bulk reinstate cascade. */
export interface BulkReinstateListingsRequest {
  notes?: string;
}

/**
 * Why an SL-group registration has been flagged as drifted. Backend uses raw
 * strings on {@code AdminSlGroupRowDto.driftReason} / {@code SlGroupRecheckResultDto.driftReason} —
 * keep this union as the canonical set of values.
 */
export type SlGroupDriftReason =
  | "FOUNDER_CHANGED"
  | "GROUP_NOT_FOUND"
  | "FETCH_FAILED_REPEATEDLY";

/**
 * Admin-facing wire shape for an SL-group registration row. Backend record
 * {@code com.slparcelauctions.backend.realty.slgroup.admin.dto.AdminSlGroupRowDto}.
 *
 * <p>Distinct from {@link RealtyGroupSlGroup} (the public surface) because
 * admins see the full drift/unregister provenance that group members do not.
 */
export interface AdminRealtyGroupSlGroup {
  publicId: string;
  slGroupUuid: string;
  slGroupName: string | null;
  verified: boolean;
  verifiedAt: string | null;
  verifiedVia: SlGroupVerifyMethod | null;
  founderAvatarUuid: string | null;
  currentFounderUuid: string | null;
  lastRevalidatedAt: string | null;
  consecutiveFetchFailures: number;
  driftDetectedAt: string | null;
  driftReason: SlGroupDriftReason | string | null;
  driftAcknowledgedAt: string | null;
  driftAcknowledgedByAdmin: AdminSummaryDto | null;
  unregisteredAt: string | null;
  unregisteredByAdmin: AdminSummaryDto | null;
  unregisterReason: string | null;
}

/**
 * Response body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/recheck}.
 * Mirrors the backend {@code SlGroupRecheckResultDto}.
 */
export interface SlGroupRecheckResult {
  driftDetected: boolean;
  driftReason: SlGroupDriftReason | string | null;
  currentFounderUuid: string | null;
}

/** Request body for ack-drift admin action. */
export interface AckDriftRequest {
  notes?: string;
}

/**
 * Request body for the force-unregister admin action (DELETE with body).
 * {@code reason} is required; recorded on the row's {@code unregister_reason}
 * column and the audit row.
 */
export interface ForceUnregisterRequest {
  reason: string;
}

/**
 * Atomic-batch payload for
 * {@code PATCH /api/v1/realty-groups/{publicId}/members/commission-rates}.
 *
 * <p>Every entry's {@code rate} replaces that member's stored agent
 * commission rate. Backend wire type is {@code BigDecimal}; we send it as
 * a string to preserve precision (e.g. {@code "0.025"} for 2.5%).
 */
export interface BulkCommissionRatesRequest {
  memberRates: BulkCommissionRateEntry[];
}

export interface BulkCommissionRateEntry {
  memberPublicId: string;
  /** Decimal as string, {@code >= "0"}. */
  rate: string;
}

/**
 * Per-member row of the realty-group commission analytics view (spec §6.8,
 * §15.2). Backend record {@code MemberCommissionRowDto}.
 *
 * <p>Both totals are integer L$ amounts ({@code >= 0}). One row per current
 * member; empty groups still return a row per member with zero totals.
 */
export interface MemberCommissionRow {
  memberPublicId: string;
  displayName: string;
  lifetimeLindens: number;
  last30DaysLindens: number;
}

/**
 * Aggregated star rating for a realty group, derived from {@code reviews}
 * rows joined via case-1 / case-3 auction linkage. Backend record
 * {@code GroupRatingDto}.
 *
 * <p>{@code averageRating} is null when no reviews exist — the "No reviews
 * yet" empty state is driven by that signal (a 0.0 placeholder would be
 * visually indistinguishable from a one-star group).
 */
export interface GroupRating {
  averageRating: number | null;
  reviewCount: number;
}
