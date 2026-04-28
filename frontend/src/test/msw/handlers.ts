// frontend/src/test/msw/handlers.ts
import { http, HttpResponse } from "msw";
import {
  mockUser,
  mockAuthResponse,
  mockUnverifiedCurrentUser,
  mockVerifiedCurrentUser,
  mockPublicProfile,
  mockValidationProblemDetail,
  mockUploadTooLargeProblemDetail,
  mockUnsupportedFormatProblemDetail,
  mockUserNotFoundProblemDetail,
  mockVerificationNotFoundProblemDetail,
  mockAlreadyVerifiedProblemDetail,
} from "./fixtures";
import type { CurrentUser, PublicUserProfile, UpdateProfileRequest } from "@/lib/user/api";
import type { NotificationDto } from "@/lib/notifications/types";
import type { PreferencesDto, EditableGroup } from "@/lib/notifications/preferencesTypes";
import type {
  AdminBanRow,
  AdminFraudFlagDetail,
  AdminFraudFlagSummary,
  AdminReportDetail,
  AdminReportListingRow,
  AdminStatsResponse,
  AdminUserDetail,
  AdminUserFraudFlagRow,
  AdminUserModerationRow,
  AdminUserSummary,
  MyReportResponse,
  UserIpProjection,
} from "@/lib/admin/types";
import type { AdminAuditLogRow } from "@/lib/admin/auditLog";
import type {
  AdminDisputeQueueRow,
  AdminDisputeDetail,
  AdminDisputeResolveResponse,
} from "@/lib/admin/disputes";
import type {
  BotPoolHealthRow,
  AdminTerminalRow,
  TerminalRotationResponse,
  ReconciliationRunRow,
  WithdrawalRow,
} from "@/lib/admin/infrastructure";
import type { Page } from "@/types/page";

/**
 * Named handler factories for every auth endpoint, plus a `defaultHandlers`
 * export used by `vitest.setup.ts` to seed the server with the "logged out"
 * baseline.
 *
 * Per-test overrides:
 *   server.use(authHandlers.loginSuccess())
 *   server.use(authHandlers.registerEmailExists())
 *
 * Handler factories take optional fixture parameters (e.g., a custom user) so
 * tests can specialize without rebuilding the response from scratch.
 */
export const authHandlers = {
  // Default bootstrap state: no session cookie → 401 AUTH_TOKEN_MISSING.
  refreshUnauthenticated: () =>
    http.post("*/api/v1/auth/refresh", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/token-missing",
          title: "Authentication required",
          status: 401,
          detail: "Authentication is required to access this resource.",
          code: "AUTH_TOKEN_MISSING",
        },
        { status: 401 }
      )
    ),

  refreshSuccess: (user = mockUser) =>
    http.post("*/api/v1/auth/refresh", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      })
    ),

  registerSuccess: (user = mockUser) =>
    http.post("*/api/v1/auth/register", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 201,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      })
    ),

  registerEmailExists: () =>
    http.post("*/api/v1/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/email-exists",
          title: "Email already registered",
          status: 409,
          detail: "An account with that email already exists.",
          code: "AUTH_EMAIL_EXISTS",
        },
        { status: 409 }
      )
    ),

  registerValidationError: (errors: { field: string; message: string }[]) =>
    http.post("*/api/v1/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/validation",
          title: "Validation failed",
          status: 400,
          detail: `Request contains ${errors.length} invalid field(s).`,
          code: "VALIDATION_FAILED",
          errors,
        },
        { status: 400 }
      )
    ),

  loginSuccess: (user = mockUser) =>
    http.post("*/api/v1/auth/login", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      })
    ),

  loginInvalidCredentials: () =>
    http.post("*/api/v1/auth/login", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/invalid-credentials",
          title: "Invalid credentials",
          status: 401,
          detail: "Email or password is incorrect.",
          code: "AUTH_INVALID_CREDENTIALS",
        },
        { status: 401 }
      )
    ),

  logoutSuccess: () =>
    http.post("*/api/v1/auth/logout", () =>
      new HttpResponse(null, { status: 204 })
    ),

  logoutAllSuccess: () =>
    http.post("*/api/v1/auth/logout-all", () =>
      new HttpResponse(null, { status: 204 })
    ),
};

export const userHandlers = {
  meUnverified: (user: CurrentUser = mockUnverifiedCurrentUser) =>
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),

  meVerified: (user: CurrentUser = mockVerifiedCurrentUser) =>
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),

  meError: () =>
    http.get("*/api/v1/users/me", () =>
      HttpResponse.json(
        { status: 500, title: "Internal Server Error" },
        { status: 500 }
      )
    ),

  updateMeSuccess: (base: CurrentUser = mockVerifiedCurrentUser) =>
    http.put("*/api/v1/users/me", async ({ request }) => {
      const body = (await request.json()) as UpdateProfileRequest;
      return HttpResponse.json({
        ...base,
        displayName: body.displayName ?? base.displayName,
        bio: body.bio ?? base.bio,
        updatedAt: new Date().toISOString(),
      });
    }),

  updateMeValidationError: () =>
    http.put("*/api/v1/users/me", () =>
      HttpResponse.json(mockValidationProblemDetail, { status: 400 })
    ),

  uploadAvatarSuccess: (user: CurrentUser = mockVerifiedCurrentUser) =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json({ ...user, updatedAt: new Date().toISOString() })
    ),

  uploadAvatarOversized: () =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json(mockUploadTooLargeProblemDetail, { status: 413 })
    ),

  uploadAvatarUnsupportedFormat: () =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json(mockUnsupportedFormatProblemDetail, { status: 400 })
    ),

  publicProfileSuccess: (profile: PublicUserProfile = mockPublicProfile) =>
    http.get("*/api/v1/users/:id", () => HttpResponse.json(profile)),

  publicProfileNotFound: () =>
    http.get("*/api/v1/users/:id", () =>
      HttpResponse.json(mockUserNotFoundProblemDetail, { status: 404 })
    ),
};

export const savedHandlers = {
  idsEmpty: () =>
    http.get("*/api/v1/me/saved/ids", () =>
      HttpResponse.json({ ids: [] as number[] }),
    ),

  idsPopulated: (ids: number[]) =>
    http.get("*/api/v1/me/saved/ids", () => HttpResponse.json({ ids })),

  auctionsEmpty: () =>
    http.get("*/api/v1/me/saved/auctions", () =>
      HttpResponse.json({
        content: [],
        page: 0,
        size: 24,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      }),
    ),

  saveSuccess: (auctionId: number) =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        { auctionId, savedAt: "2026-04-23T00:00:00Z" },
        { status: 201 },
      ),
    ),

  saveLimitReached: () =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        { status: 409, code: "SAVED_LIMIT_REACHED", title: "Limit reached" },
        { status: 409 },
      ),
    ),

  savePreActive: () =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        {
          status: 403,
          code: "CANNOT_SAVE_PRE_ACTIVE",
          title: "Not available",
        },
        { status: 403 },
      ),
    ),

  unsaveSuccess: () =>
    http.delete("*/api/v1/me/saved/:id", () =>
      new HttpResponse(null, { status: 204 }),
    ),
};

export const verificationHandlers = {
  activeNone: () =>
    http.get("*/api/v1/verification/active", () =>
      HttpResponse.json(mockVerificationNotFoundProblemDetail, { status: 404 })
    ),

  activeExists: (code = "123456", expiresAt = "2026-04-14T21:00:00Z") =>
    http.get("*/api/v1/verification/active", () =>
      HttpResponse.json({ code, expiresAt })
    ),

  generateSuccess: (code = "654321", expiresAt = "2026-04-14T21:15:00Z") =>
    http.post("*/api/v1/verification/generate", () =>
      HttpResponse.json({ code, expiresAt })
    ),

  generateAlreadyVerified: () =>
    http.post("*/api/v1/verification/generate", () =>
      HttpResponse.json(mockAlreadyVerifiedProblemDetail, { status: 409 })
    ),
};

// In-memory store for notification handler tests.
const _notifications = new Map<number, NotificationDto>();
let _nextNotifId = 1;

export const notificationHandlers = [
  http.get("*/api/v1/notifications", () => {
    const all = Array.from(_notifications.values())
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    return HttpResponse.json({
      content: all,
      totalElements: all.length,
      totalPages: 1,
      number: 0,
      size: 20,
    });
  }),
  http.get("*/api/v1/notifications/unread-count", ({ request }) => {
    const url = new URL(request.url);
    const breakdown = url.searchParams.get("breakdown");
    const count = Array.from(_notifications.values()).filter((n) => !n.read).length;
    if (breakdown === "group") {
      const byGroup: Record<string, number> = {};
      for (const n of _notifications.values()) {
        if (!n.read) byGroup[n.group] = (byGroup[n.group] ?? 0) + 1;
      }
      return HttpResponse.json({ count, byGroup });
    }
    return HttpResponse.json({ count });
  }),
  http.put("*/api/v1/notifications/:id/read", ({ params }) => {
    const n = _notifications.get(Number(params.id));
    if (!n) return new HttpResponse(null, { status: 404 });
    n.read = true;
    return new HttpResponse(null, { status: 204 });
  }),
  http.put("*/api/v1/notifications/read-all", ({ request }) => {
    const url = new URL(request.url);
    const group = url.searchParams.get("group");
    let count = 0;
    for (const n of _notifications.values()) {
      if (!group || n.group === group.toLowerCase()) {
        if (!n.read) { n.read = true; count++; }
      }
    }
    return HttpResponse.json({ markedRead: count });
  }),
];

export function seedNotification(partial: Partial<NotificationDto> = {}): NotificationDto {
  const id = _nextNotifId++;
  const n: NotificationDto = {
    id,
    category: "OUTBID",
    group: "bidding",
    title: "You were outbid",
    body: "Someone placed a higher bid.",
    data: {},
    read: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...partial,
  };
  _notifications.set(id, n);
  return n;
}

export function clearNotifications(): void {
  _notifications.clear();
  _nextNotifId = 1;
}

// ---------------------------------------------------------------------------
// Notification Preferences handlers
// ---------------------------------------------------------------------------

const DEFAULT_PREFERENCES: PreferencesDto = {
  slImMuted: false,
  slIm: {
    bidding: true,
    auction_result: true,
    escrow: true,
    listing_status: true,
    reviews: false,
  },
};

let _currentPreferences: PreferencesDto = { ...DEFAULT_PREFERENCES, slIm: { ...DEFAULT_PREFERENCES.slIm } };
const ALLOWED_PREFS_KEYS: Set<string> = new Set<EditableGroup>([
  "bidding",
  "auction_result",
  "escrow",
  "listing_status",
  "reviews",
]);

export const preferencesHandlers = [
  http.get("*/api/v1/users/me/notification-preferences", () => {
    return HttpResponse.json(_currentPreferences);
  }),
  http.put(
    "*/api/v1/users/me/notification-preferences",
    async ({ request }) => {
      const body = (await request.json()) as PreferencesDto;
      if (!body.slIm) {
        return new HttpResponse(null, { status: 400 });
      }
      const keys = new Set(Object.keys(body.slIm));
      if (
        keys.size !== ALLOWED_PREFS_KEYS.size ||
        ![...keys].every((k) => ALLOWED_PREFS_KEYS.has(k))
      ) {
        return new HttpResponse(null, { status: 400 });
      }
      for (const v of Object.values(body.slIm)) {
        if (typeof v !== "boolean") {
          return new HttpResponse(null, { status: 400 });
        }
      }
      if (typeof body.slImMuted !== "boolean") {
        return new HttpResponse(null, { status: 400 });
      }
      _currentPreferences = body;
      return HttpResponse.json(_currentPreferences);
    }
  ),
];

export function seedPreferences(p: PreferencesDto): void {
  _currentPreferences = { ...p, slIm: { ...p.slIm } };
}

export function resetPreferences(): void {
  _currentPreferences = { ...DEFAULT_PREFERENCES, slIm: { ...DEFAULT_PREFERENCES.slIm } };
}

const defaultStats: AdminStatsResponse = {
  queues: { openFraudFlags: 0, openReports: 0, pendingPayments: 0, activeDisputes: 0 },
  platform: {
    activeListings: 0,
    totalUsers: 0,
    activeEscrows: 0,
    completedSales: 0,
    lindenGrossVolume: 0,
    lindenCommissionEarned: 0,
  },
};

export const adminHandlers = {
  statsSuccess(stats: Partial<AdminStatsResponse> = {}) {
    return http.get("*/api/v1/admin/stats", () =>
      HttpResponse.json({ ...defaultStats, ...stats })
    );
  },

  fraudFlagsListSuccess(rows: AdminFraudFlagSummary[]) {
    return http.get("*/api/v1/admin/fraud-flags", () => {
      const page: Page<AdminFraudFlagSummary> = {
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      };
      return HttpResponse.json(page);
    });
  },

  fraudFlagDetailSuccess(detail: AdminFraudFlagDetail) {
    return http.get(`*/api/v1/admin/fraud-flags/${detail.id}`, () =>
      HttpResponse.json(detail)
    );
  },

  dismissSuccess(detail: AdminFraudFlagDetail) {
    return http.post(`*/api/v1/admin/fraud-flags/${detail.id}/dismiss`, () =>
      HttpResponse.json(detail)
    );
  },

  reinstateSuccess(detail: AdminFraudFlagDetail) {
    return http.post(`*/api/v1/admin/fraud-flags/${detail.id}/reinstate`, () =>
      HttpResponse.json(detail)
    );
  },

  dismiss409AlreadyResolved(flagId: number) {
    return http.post(`*/api/v1/admin/fraud-flags/${flagId}/dismiss`, () =>
      HttpResponse.json(
        { code: "ALREADY_RESOLVED", message: "Already resolved", details: {} },
        { status: 409 }
      )
    );
  },

  reinstate409NotSuspended(flagId: number, currentStatus: string) {
    return http.post(`*/api/v1/admin/fraud-flags/${flagId}/reinstate`, () =>
      HttpResponse.json(
        {
          code: "AUCTION_NOT_SUSPENDED",
          message: "Not suspended",
          details: { currentStatus },
        },
        { status: 409 }
      )
    );
  },

  reportsListSuccess(rows: AdminReportListingRow[]) {
    return http.get("*/api/v1/admin/reports", () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  reportsByListingSuccess(auctionId: number, reports: AdminReportDetail[]) {
    return http.get(`*/api/v1/admin/reports/listing/${auctionId}`, () =>
      HttpResponse.json(reports)
    );
  },

  reportDetailSuccess(report: AdminReportDetail) {
    return http.get(`*/api/v1/admin/reports/${report.id}`, () =>
      HttpResponse.json(report)
    );
  },

  dismissReportSuccess(report: AdminReportDetail) {
    return http.post(`*/api/v1/admin/reports/${report.id}/dismiss`, () =>
      HttpResponse.json(report)
    );
  },

  warnSellerSuccess(auctionId: number) {
    return http.post(`*/api/v1/admin/reports/listing/${auctionId}/warn-seller`, () =>
      new HttpResponse(null, { status: 200 })
    );
  },

  suspendListingFromReportSuccess(auctionId: number) {
    return http.post(`*/api/v1/admin/reports/listing/${auctionId}/suspend`, () =>
      new HttpResponse(null, { status: 200 })
    );
  },

  cancelListingFromReportSuccess(auctionId: number) {
    return http.post(`*/api/v1/admin/reports/listing/${auctionId}/cancel`, () =>
      new HttpResponse(null, { status: 200 })
    );
  },

  bansListSuccess(rows: AdminBanRow[]) {
    return http.get("*/api/v1/admin/bans", () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  createBanSuccess(ban: AdminBanRow) {
    return http.post("*/api/v1/admin/bans", () => HttpResponse.json(ban));
  },

  liftBanSuccess(ban: AdminBanRow) {
    return http.post(`*/api/v1/admin/bans/${ban.id}/lift`, () => HttpResponse.json(ban));
  },

  ban409AlreadyLifted(banId: number) {
    return http.post(`*/api/v1/admin/bans/${banId}/lift`, () =>
      HttpResponse.json(
        { code: "BAN_ALREADY_LIFTED", message: "Ban already lifted", details: {} },
        { status: 409 }
      )
    );
  },

  ban409TypeFieldMismatch(banId: number) {
    return http.post(`*/api/v1/admin/bans/${banId}/lift`, () =>
      HttpResponse.json(
        { code: "BAN_TYPE_FIELD_MISMATCH", message: "Ban type field mismatch", details: {} },
        { status: 409 }
      )
    );
  },

  usersSearchSuccess(rows: AdminUserSummary[]) {
    return http.get("*/api/v1/admin/users", () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  userDetailSuccess(detail: AdminUserDetail) {
    return http.get(`*/api/v1/admin/users/${detail.id}`, () => HttpResponse.json(detail));
  },

  userIpsSuccess(userId: number, ips: UserIpProjection[]) {
    return http.get(`*/api/v1/admin/users/${userId}/ips`, () => HttpResponse.json(ips));
  },

  userListingsSuccess(userId: number, rows: AdminUserModerationRow[]) {
    return http.get(`*/api/v1/admin/users/${userId}/listings`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  userBidsSuccess(userId: number, rows: AdminUserModerationRow[]) {
    return http.get(`*/api/v1/admin/users/${userId}/bids`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  userCancellationsSuccess(userId: number, rows: AdminUserModerationRow[]) {
    return http.get(`*/api/v1/admin/users/${userId}/cancellations`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  userReportsSuccess(userId: number, rows: AdminUserModerationRow[]) {
    return http.get(`*/api/v1/admin/users/${userId}/reports`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  userFraudFlagsSuccess(userId: number, rows: AdminUserFraudFlagRow[]) {
    return http.get(`*/api/v1/admin/users/${userId}/fraud-flags`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  userModerationSuccess(userId: number, rows: AdminUserModerationRow[]) {
    return http.get(`*/api/v1/admin/users/${userId}/moderation`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  promoteUserSuccess(userId: number) {
    return http.post(`*/api/v1/admin/users/${userId}/promote`, () =>
      new HttpResponse(null, { status: 200 })
    );
  },

  promoteUser409AlreadyAdmin(userId: number) {
    return http.post(`*/api/v1/admin/users/${userId}/promote`, () =>
      HttpResponse.json(
        { code: "ALREADY_ADMIN", message: "User is already an admin", details: {} },
        { status: 409 }
      )
    );
  },

  demoteUserSuccess(userId: number) {
    return http.post(`*/api/v1/admin/users/${userId}/demote`, () =>
      new HttpResponse(null, { status: 200 })
    );
  },

  demoteUser409SelfForbidden(userId: number) {
    return http.post(`*/api/v1/admin/users/${userId}/demote`, () =>
      HttpResponse.json(
        { code: "SELF_DEMOTE_FORBIDDEN", message: "Cannot demote yourself", details: {} },
        { status: 409 }
      )
    );
  },

  demoteUser409NotAdmin(userId: number) {
    return http.post(`*/api/v1/admin/users/${userId}/demote`, () =>
      HttpResponse.json(
        { code: "NOT_ADMIN", message: "User is not an admin", details: {} },
        { status: 409 }
      )
    );
  },

  resetFrivolousCounterSuccess(userId: number) {
    return http.post(`*/api/v1/admin/users/${userId}/reset-frivolous-counter`, () =>
      new HttpResponse(null, { status: 200 })
    );
  },

  auditListSuccess(rows: AdminUserModerationRow[]) {
    return http.get("*/api/v1/admin/audit", () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      })
    );
  },

  submitReportSuccess(auctionId: number, response: MyReportResponse) {
    return http.post(`*/api/v1/auctions/${auctionId}/report`, () =>
      HttpResponse.json(response)
    );
  },

  myReport204(auctionId: number) {
    return http.get(`*/api/v1/auctions/${auctionId}/my-report`, () =>
      new HttpResponse(null, { status: 204 })
    );
  },

  myReportSuccess(auctionId: number, response: MyReportResponse) {
    return http.get(`*/api/v1/auctions/${auctionId}/my-report`, () =>
      HttpResponse.json(response)
    );
  },
};

export const adminDisputesHandlers = {
  listEmpty: () =>
    http.get("*/api/v1/admin/disputes", () =>
      HttpResponse.json({ content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 })
    ),
  listWithItems: (rows: AdminDisputeQueueRow[]) =>
    http.get("*/api/v1/admin/disputes", () =>
      HttpResponse.json({ content: rows, number: 0, size: 20, totalElements: rows.length, totalPages: 1 })
    ),
  detail: (escrowId: number, body: AdminDisputeDetail) =>
    http.get(`*/api/v1/admin/disputes/${escrowId}`, () => HttpResponse.json(body)),
  resolveSuccess: (escrowId: number, response: AdminDisputeResolveResponse) =>
    http.post(`*/api/v1/admin/disputes/${escrowId}/resolve`, () => HttpResponse.json(response)),
  resolve409: (escrowId: number) =>
    http.post(`*/api/v1/admin/disputes/${escrowId}/resolve`, () =>
      HttpResponse.json({ code: "DISPUTE_ACTION_INVALID_FOR_STATE" }, { status: 409 })),
};

export const adminBotPoolHandlers = {
  healthEmpty: () =>
    http.get("*/api/v1/admin/bot-pool/health", () => HttpResponse.json([])),
  healthWithRows: (rows: BotPoolHealthRow[]) =>
    http.get("*/api/v1/admin/bot-pool/health", () => HttpResponse.json(rows)),
};

export const adminTerminalsHandlers = {
  listEmpty: () =>
    http.get("*/api/v1/admin/terminals", () => HttpResponse.json([])),
  listWithItems: (rows: AdminTerminalRow[]) =>
    http.get("*/api/v1/admin/terminals", () => HttpResponse.json(rows)),
  rotateSuccess: (response: TerminalRotationResponse) =>
    http.post("*/api/v1/admin/terminals/rotate-secret", () => HttpResponse.json(response)),
};

export const adminReconciliationHandlers = {
  runsEmpty: () =>
    http.get("*/api/v1/admin/reconciliation/runs", () => HttpResponse.json([])),
  runsWithItems: (rows: ReconciliationRunRow[]) =>
    http.get("*/api/v1/admin/reconciliation/runs", () => HttpResponse.json(rows)),
};

export const adminWithdrawalsHandlers = {
  listEmpty: () =>
    http.get("*/api/v1/admin/withdrawals", () =>
      HttpResponse.json({ content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 })
    ),
  listWithItems: (rows: WithdrawalRow[]) =>
    http.get("*/api/v1/admin/withdrawals", () =>
      HttpResponse.json({ content: rows, number: 0, size: 20, totalElements: rows.length, totalPages: 1 })
    ),
  available: (amount: number) =>
    http.get("*/api/v1/admin/withdrawals/available", () =>
      HttpResponse.json({ available: amount })
    ),
  createSuccess: (response: WithdrawalRow) =>
    http.post("*/api/v1/admin/withdrawals", () => HttpResponse.json(response)),
  createInsufficient: () =>
    http.post("*/api/v1/admin/withdrawals", () =>
      HttpResponse.json({ code: "INSUFFICIENT_BALANCE" }, { status: 409 })
    ),
};

export const adminOwnershipRecheckHandlers = {
  matchSuccess: (auctionId: number, observedOwner: string, expectedOwner: string) =>
    http.post(`*/api/v1/admin/auctions/${auctionId}/recheck-ownership`, () =>
      HttpResponse.json({
        ownerMatch: true,
        observedOwner,
        expectedOwner,
        checkedAt: new Date().toISOString(),
        auctionStatus: "ACTIVE",
      })
    ),
  mismatchSuspended: (auctionId: number, observedOwner: string, expectedOwner: string) =>
    http.post(`*/api/v1/admin/auctions/${auctionId}/recheck-ownership`, () =>
      HttpResponse.json({
        ownerMatch: false,
        observedOwner,
        expectedOwner,
        checkedAt: new Date().toISOString(),
        auctionStatus: "SUSPENDED",
      })
    ),
};

export const adminAuditLogHandlers = {
  listEmpty: () =>
    http.get("*/api/v1/admin/audit-log", () =>
      HttpResponse.json({ content: [], number: 0, size: 50, totalElements: 0, totalPages: 0 })
    ),
  listWithItems: (rows: AdminAuditLogRow[]) =>
    http.get("*/api/v1/admin/audit-log", () =>
      HttpResponse.json({ content: rows, number: 0, size: 50, totalElements: rows.length, totalPages: 1 })
    ),
};

export const adminUserDeletionHandlers = {
  deleteSuccess: (userId: number) =>
    http.delete(`*/api/v1/admin/users/${userId}`, () => new HttpResponse(null, { status: 204 })),
  delete409Auctions: (userId: number, auctionIds: number[]) =>
    http.delete(`*/api/v1/admin/users/${userId}`, () =>
      HttpResponse.json({ code: "ACTIVE_AUCTIONS", message: "blocked", blockingIds: auctionIds }, { status: 409 })
    ),
};

export const userDeletionHandlers = {
  deleteSelfSuccess: () =>
    http.delete("*/api/v1/users/me", () => new HttpResponse(null, { status: 204 })),
  deleteSelf403WrongPassword: () =>
    http.delete("*/api/v1/users/me", () =>
      HttpResponse.json({ status: 403, code: "INVALID_PASSWORD", message: "wrong" }, { status: 403 })
    ),
  deleteSelf409Auctions: (auctionIds: number[]) =>
    http.delete("*/api/v1/users/me", () =>
      HttpResponse.json(
        { status: 409, code: "ACTIVE_AUCTIONS", message: "blocked", blockingIds: auctionIds },
        { status: 409 }
      )
    ),
};

/**
 * Default handlers registered at server startup. Establishes the "no session"
 * baseline so tests that don't explicitly authenticate get the unauthenticated
 * bootstrap path automatically.
 */
export const defaultHandlers = [authHandlers.refreshUnauthenticated()];
