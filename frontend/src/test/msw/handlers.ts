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
  mockSuggestResponse,
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
 *   server.use(authHandlers.registerUsernameExists())
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

  registerUsernameExists: () =>
    http.post("*/api/v1/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/username-exists",
          title: "Username already taken",
          status: 409,
          detail: "An account with that username already exists.",
          code: "AUTH_USERNAME_EXISTS",
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
          detail: "Username or password is incorrect.",
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

  realtyGroupsEmpty: () =>
    http.get("*/api/v1/users/:id/realty-groups", () => HttpResponse.json([])),

  realtyGroupsSuccess: <T>(affiliations: T[]) =>
    http.get("*/api/v1/users/:id/realty-groups", () =>
      HttpResponse.json(affiliations),
    ),
};

export const savedHandlers = {
  idsEmpty: () =>
    http.get("*/api/v1/me/saved/ids", () =>
      HttpResponse.json({ publicIds: [] as string[] }),
    ),

  idsPopulated: (publicIds: string[]) =>
    http.get("*/api/v1/me/saved/ids", () => HttpResponse.json({ publicIds })),

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

  saveSuccess: (auctionPublicId: string) =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        { auctionPublicId, savedAt: "2026-04-23T00:00:00Z" },
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
const _notifications = new Map<string, NotificationDto>();
let _nextNotifIdNum = 1;

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
  http.put("*/api/v1/notifications/:publicId/read", ({ params }) => {
    const n = _notifications.get(String(params.publicId));
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
  http.get("*/api/v1/search/suggest", ({ request }) => {
    const url = new URL(request.url);
    const q = url.searchParams.get("q") ?? "";
    if (q.trim().length < 2) {
      return HttpResponse.json({ listings: [], regions: [], totalListings: 0 });
    }
    return HttpResponse.json(mockSuggestResponse());
  }),
];

export function seedNotification(partial: Partial<NotificationDto> = {}): NotificationDto {
  const seq = _nextNotifIdNum++;
  const publicId = `00000000-0000-0000-0000-${String(seq).padStart(12, "0")}`;
  const n: NotificationDto = {
    publicId,
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
  _notifications.set(n.publicId, n);
  return n;
}

export function clearNotifications(): void {
  _notifications.clear();
  _nextNotifIdNum = 1;
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
    return http.get(`*/api/v1/admin/users/${detail.publicId}`, () => HttpResponse.json(detail));
  },

  userIpsSuccess(publicId: string, ips: UserIpProjection[]) {
    return http.get(`*/api/v1/admin/users/${publicId}/ips`, () => HttpResponse.json(ips));
  },

  userListingsSuccess(publicId: string, rows: AdminUserModerationRow[]) {
    return http.get(`*/api/v1/admin/users/${publicId}/listings`, () =>
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

  demoteUser409SelfForbidden(userId: string | number) {
    return http.post(`*/api/v1/admin/users/${userId}/demote`, () =>
      HttpResponse.json(
        { code: "SELF_DEMOTE_FORBIDDEN", message: "Cannot demote yourself", details: {} },
        { status: 409 }
      )
    );
  },

  demoteUser409NotAdmin(userId: string | number) {
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

  submitReportSuccess(auctionPublicId: string, response: MyReportResponse) {
    return http.post(`*/api/v1/auctions/${auctionPublicId}/report`, () =>
      HttpResponse.json(response)
    );
  },

  myReport204(auctionPublicId: string) {
    return http.get(`*/api/v1/auctions/${auctionPublicId}/my-report`, () =>
      new HttpResponse(null, { status: 204 })
    );
  },

  myReportSuccess(auctionPublicId: string, response: MyReportResponse) {
    return http.get(`*/api/v1/auctions/${auctionPublicId}/my-report`, () =>
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
  deleteSuccess: (publicId: string) =>
    http.delete(`*/api/v1/admin/users/${publicId}`, () => new HttpResponse(null, { status: 204 })),
  delete409Auctions: (publicId: string, auctionIds: number[]) =>
    http.delete(`*/api/v1/admin/users/${publicId}`, () =>
      HttpResponse.json(
        { status: 409, code: "ACTIVE_AUCTIONS", message: "blocked", blockingIds: auctionIds },
        { status: 409 }
      )
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

// ---------------------------------------------------------------------------
// Realty group wallet handlers
// ---------------------------------------------------------------------------

export const realtyGroupWalletHandlers = {
  /**
   * Default GET wallet — zero-balance group with empty ledger.
   * Matches any group public ID.
   */
  walletEmpty: () =>
    http.get("*/api/v1/realty/groups/:publicId/wallet", () =>
      HttpResponse.json({
        balance: 0,
        reserved: 0,
        available: 0,
        recentLedger: [],
      }),
    ),

  walletSuccess: (publicId: string, overrides: Record<string, unknown> = {}) =>
    http.get(`*/api/v1/realty/groups/${publicId}/wallet`, () =>
      HttpResponse.json({
        balance: 0,
        reserved: 0,
        available: 0,
        recentLedger: [],
        ...overrides,
      }),
    ),

  /** Default GET ledger — empty array. Matches any group public ID. */
  ledgerEmpty: () =>
    http.get("*/api/v1/realty/groups/:publicId/wallet/ledger", () =>
      HttpResponse.json([]),
    ),

  ledgerSuccess: <T>(publicId: string, entries: T[]) =>
    http.get(`*/api/v1/realty/groups/${publicId}/wallet/ledger`, () =>
      HttpResponse.json(entries),
    ),

  /** Default POST withdraw — 202 with queueId=1. */
  withdrawSuccess: () =>
    http.post("*/api/v1/realty/groups/:publicId/wallet/withdraw", () =>
      HttpResponse.json(
        { queueId: 1, estimatedFulfillmentSeconds: 30 },
        { status: 202 },
      ),
    ),

  /**
   * Sub-project G §7.3 — happy-path POST withdraw that echoes the recipient
   * back in the response so tests can assert the {@code recipient} field made
   * it onto the wire. The echo is test-only and not part of the production
   * wire shape (which is just {@code queueId} + {@code estimatedFulfillmentSeconds}).
   */
  withdrawSuccessEchoingRecipient: () =>
    http.post(
      "*/api/v1/realty/groups/:publicId/wallet/withdraw",
      async ({ request }) => {
        const body = (await request.json()) as { recipient?: string };
        return HttpResponse.json(
          {
            queueId: 1,
            estimatedFulfillmentSeconds: 60,
            // Test-only echo — production response omits this field.
            echoedRecipient: body.recipient ?? null,
          },
          { status: 202 },
        );
      },
    ),

  /** Sub-project G §7.3 — 422 when caller picks SL_GROUP but none is registered. */
  withdrawSlGroupNotRegistered: () =>
    http.post("*/api/v1/realty/groups/:publicId/wallet/withdraw", () =>
      HttpResponse.json(
        {
          status: 422,
          code: "SL_GROUP_NOT_REGISTERED",
          title: "SL group not registered",
          detail:
            "This realty group has no currently-registered SL group. Choose the leader's avatar or register an SL group first.",
        },
        { status: 422 },
      ),
    ),

  /**
   * Sub-project G §7.3 — 422 when the realty group has an active suspension
   * and the caller asked to route to SL_GROUP. The AVATAR recipient remains
   * available.
   */
  withdrawSlGroupRegistrationSuspended: () =>
    http.post("*/api/v1/realty/groups/:publicId/wallet/withdraw", () =>
      HttpResponse.json(
        {
          status: 422,
          code: "SL_GROUP_REGISTRATION_SUSPENDED",
          title: "SL group registration suspended",
          detail:
            "SL-group withdrawals are blocked while the realty group is suspended.",
        },
        { status: 422 },
      ),
    ),

  withdrawInsufficientBalance: (available: number, requested: number) =>
    http.post("*/api/v1/realty/groups/:publicId/wallet/withdraw", () =>
      HttpResponse.json(
        {
          status: 422,
          code: "INSUFFICIENT_GROUP_BALANCE",
          title: "Insufficient group balance",
          detail: "The group wallet does not have enough available funds.",
          available,
          requested,
        },
        { status: 422 },
      ),
    ),

  withdraw403: () =>
    http.post("*/api/v1/realty/groups/:publicId/wallet/withdraw", () =>
      HttpResponse.json(
        {
          status: 403,
          code: "INSUFFICIENT_GROUP_PERMISSION",
          title: "Insufficient group permission",
        },
        { status: 403 },
      ),
    ),

  withdraw410: () =>
    http.post("*/api/v1/realty/groups/:publicId/wallet/withdraw", () =>
      HttpResponse.json(
        {
          status: 410,
          code: "GROUP_DISSOLVED",
          title: "Group is dissolved",
        },
        { status: 410 },
      ),
    ),
};

// ---------------------------------------------------------------------------
// Realty group SL-group registration handlers (Realty Groups: E)
// ---------------------------------------------------------------------------

/**
 * MSW factories for the four endpoints under
 * {@code /api/v1/realty/groups/:publicId/sl-groups}, plus the updated
 * {@code GET /api/v1/realty/me/listing-eligible-groups?slParcelUuid=...}
 * variant. Pattern mirrors {@code realtyGroupWalletHandlers}: a default
 * happy-path factory per endpoint and named error variants that tests opt
 * into via {@code server.use(...)}.
 *
 * Default GET list resolves to an empty array so any component that mounts
 * the {@code useRealtyGroupSlGroups} hook doesn't have to register a
 * handler in tests that don't exercise the SL-group surface.
 */
export const realtySlGroupHandlers = {
  /** GET list — empty by default. */
  listEmpty: () =>
    http.get("*/api/v1/realty/groups/:publicId/sl-groups", () =>
      HttpResponse.json([]),
    ),

  listSuccess: <T>(entries: T[]) =>
    http.get("*/api/v1/realty/groups/:publicId/sl-groups", () =>
      HttpResponse.json(entries),
    ),

  /** POST register — returns a pending entry by default. */
  registerSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/realty/groups/:publicId/sl-groups",
      async ({ request }) => {
        const body = (await request.json()) as { slGroupUuid: string };
        return HttpResponse.json(
          {
            publicId: "11111111-1111-1111-1111-111111111111",
            slGroupUuid: body.slGroupUuid,
            slGroupName: null,
            verified: false,
            verifiedAt: null,
            verifiedVia: null,
            pending: {
              verificationCode: "SLPA-1A2B3C4D5E6F",
              verificationCodeExpiresAt: "2026-05-12T21:00:00Z",
              lastPolledAt: null,
              pollAttempts: 0,
            },
            founderAvatarUuid: null,
            ...overrides,
          },
          { status: 201 },
        );
      },
    ),

  registerAlreadyRegistered: () =>
    http.post("*/api/v1/realty/groups/:publicId/sl-groups", () =>
      HttpResponse.json(
        {
          status: 409,
          code: "SL_GROUP_ALREADY_REGISTERED",
          title: "Already registered",
          detail: "This SL group is already registered on the realty group.",
        },
        { status: 409 },
      ),
    ),

  /**
   * Sub-project G section 14 -- reverse-search ban-evasion gate. The SL group
   * UUID is currently registered to a realty group with an active (unlifted)
   * suspension row. Distinct {@code code} so the modal can render a
   * "contact support" copy instead of the generic "already registered" one.
   */
  registerToSuspendedGroup: () =>
    http.post("*/api/v1/realty/groups/:publicId/sl-groups", () =>
      HttpResponse.json(
        {
          status: 409,
          code: "SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP",
          title: "SL Group Registered To Suspended Group",
          detail:
            "This SL group is registered to a suspended SLPA realty group. Contact support.",
        },
        { status: 409 },
      ),
    ),

  registerForbidden: () =>
    http.post("*/api/v1/realty/groups/:publicId/sl-groups", () =>
      HttpResponse.json(
        {
          status: 403,
          code: "INSUFFICIENT_GROUP_PERMISSION",
          title: "Insufficient group permission",
        },
        { status: 403 },
      ),
    ),

  /** DELETE unregister — 204 by default. */
  unregisterSuccess: () =>
    http.delete(
      "*/api/v1/realty/groups/:publicId/sl-groups/:slGroupPublicId",
      () => new HttpResponse(null, { status: 204 }),
    ),

  unregisterBlockedByListings: () =>
    http.delete(
      "*/api/v1/realty/groups/:publicId/sl-groups/:slGroupPublicId",
      () =>
        HttpResponse.json(
          {
            status: 409,
            code: "SL_GROUP_HAS_ACTIVE_LISTINGS",
            title: "Active listings depend on this SL group",
          },
          { status: 409 },
        ),
    ),

  /** POST recheck — returns the row as still-pending by default. */
  recheckPending: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/realty/groups/:publicId/sl-groups/:slGroupPublicId/recheck",
      () =>
        HttpResponse.json({
          publicId: "11111111-1111-1111-1111-111111111111",
          slGroupUuid: "22222222-2222-2222-2222-222222222222",
          slGroupName: null,
          verified: false,
          verifiedAt: null,
          verifiedVia: null,
          pending: {
            verificationCode: "SLPA-1A2B3C4D5E6F",
            verificationCodeExpiresAt: "2026-05-12T21:00:00Z",
            lastPolledAt: "2026-05-12T20:35:00Z",
            pollAttempts: 1,
          },
          founderAvatarUuid: null,
          ...overrides,
        }),
    ),

  /** POST recheck — returns a now-verified row. */
  recheckVerified: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/realty/groups/:publicId/sl-groups/:slGroupPublicId/recheck",
      () =>
        HttpResponse.json({
          publicId: "11111111-1111-1111-1111-111111111111",
          slGroupUuid: "22222222-2222-2222-2222-222222222222",
          slGroupName: "Sunset Estates",
          verified: true,
          verifiedAt: "2026-05-12T20:36:00Z",
          verifiedVia: "FOUNDER_TERMINAL",
          pending: null,
          founderAvatarUuid: "33333333-3333-3333-3333-333333333333",
          ...overrides,
        }),
    ),

  /**
   * Updated listing-eligible-groups handler — asserts {@code slParcelUuid}
   * is present in the query string and returns an empty list by default.
   * Tests that need specific groups pass the {@code entries} param.
   */
  listingEligibleGroupsEmpty: () =>
    http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
      HttpResponse.json([]),
    ),

  listingEligibleGroupsSuccess: <T>(entries: T[]) =>
    http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
      HttpResponse.json(entries),
    ),
};

// ---------------------------------------------------------------------------
// Broker-cancel handler (Realty Groups: E, Task 25)
// ---------------------------------------------------------------------------

export const brokerCancelHandlers = {
  /** Echoes the auction back in CANCELLED state. */
  cancelSuccess: (
    auctionPublicId: string,
    response: Record<string, unknown>,
  ) =>
    http.post(`*/api/v1/auctions/${auctionPublicId}/broker-cancel`, () =>
      HttpResponse.json(response),
    ),

  /** 409 — the auction isn't in a state where a broker can cancel it. */
  notApplicable: (auctionPublicId: string) =>
    http.post(`*/api/v1/auctions/${auctionPublicId}/broker-cancel`, () =>
      HttpResponse.json(
        {
          status: 409,
          code: "BROKER_CANCEL_NOT_APPLICABLE",
          title: "Broker cancel not applicable",
        },
        { status: 409 },
      ),
    ),

  /** 403 — caller is not a broker on this auction's group. */
  forbidden: (auctionPublicId: string) =>
    http.post(`*/api/v1/auctions/${auctionPublicId}/broker-cancel`, () =>
      HttpResponse.json(
        {
          status: 403,
          code: "INSUFFICIENT_GROUP_PERMISSION",
          title: "Insufficient group permission",
        },
        { status: 403 },
      ),
    ),
};

// ---------------------------------------------------------------------------
// Realty Groups: F — Admin moderation MSW handlers
// ---------------------------------------------------------------------------

/**
 * MSW factories for {@code /api/v1/admin/realty-groups/:publicId/suspensions}.
 * Pattern mirrors {@code realtySlGroupHandlers}: a default happy-path factory
 * per endpoint plus named error variants.
 */
export const realtyGroupSuspensionHandlers = {
  /** GET list — empty by default. */
  listEmpty: () =>
    http.get(
      "*/api/v1/admin/realty-groups/:publicId/suspensions",
      () => HttpResponse.json([]),
    ),

  listSuccess: <T>(rows: T[]) =>
    http.get(
      "*/api/v1/admin/realty-groups/:publicId/suspensions",
      () => HttpResponse.json(rows),
    ),

  /** POST issue — returns an ACTIVE_TIMED suspension by default. */
  issueSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/suspensions",
      () =>
        HttpResponse.json(
          {
            publicId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            reason: "TOS_VIOLATION",
            notes: "Repeated TOS violations",
            issuedAt: "2026-05-12T12:00:00Z",
            expiresAt: "2026-05-19T12:00:00Z",
            liftedAt: null,
            liftedNotes: null,
            issuedByAdmin: {
              publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              displayName: "Admin User",
            },
            liftedByAdmin: null,
            status: "ACTIVE_TIMED",
            ...overrides,
          },
          { status: 201 },
        ),
    ),

  issueForbidden: () =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/suspensions",
      () =>
        HttpResponse.json(
          { status: 401, title: "Unauthorized" },
          { status: 401 },
        ),
    ),

  /** DELETE lift — 204 by default. */
  liftSuccess: () =>
    http.delete(
      "*/api/v1/admin/realty-groups/:publicId/suspensions/:suspensionPublicId",
      () => new HttpResponse(null, { status: 204 }),
    ),

  liftAlreadyLifted: () =>
    http.delete(
      "*/api/v1/admin/realty-groups/:publicId/suspensions/:suspensionPublicId",
      () =>
        HttpResponse.json(
          {
            status: 409,
            code: "SUSPENSION_ALREADY_LIFTED",
            title: "Suspension already lifted",
          },
          { status: 409 },
        ),
    ),
};

/**
 * MSW factories for the realty-group reports surface — public submit plus
 * the four admin endpoints (queue, detail, resolve, dismiss).
 */
export const realtyGroupReportHandlers = {
  /** POST public submit — 201 with a narrow {@code ReportDto}. */
  submitSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post("*/api/v1/realty-groups/:publicId/reports", () =>
      HttpResponse.json(
        {
          publicId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
          groupPublicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
          reason: "FRAUDULENT_LISTINGS",
          status: "OPEN",
          createdAt: "2026-05-12T12:00:00Z",
          ...overrides,
        },
        { status: 201 },
      ),
    ),

  submitAlreadyReported: () =>
    http.post("*/api/v1/realty-groups/:publicId/reports", () =>
      HttpResponse.json(
        {
          status: 409,
          code: "ALREADY_REPORTED",
          title: "Already reported",
          detail: "You already have an open report against this group.",
        },
        { status: 409 },
      ),
    ),

  submitOwnGroup: () =>
    http.post("*/api/v1/realty-groups/:publicId/reports", () =>
      HttpResponse.json(
        {
          status: 409,
          code: "CANNOT_REPORT_OWN_GROUP",
          title: "Cannot report own group",
        },
        { status: 409 },
      ),
    ),

  submitRateLimited: () =>
    http.post("*/api/v1/realty-groups/:publicId/reports", () =>
      HttpResponse.json(
        {
          status: 429,
          code: "REPORT_RATE_LIMITED",
          title: "Report rate limited",
          detail: "Daily report quota exhausted.",
        },
        { status: 429 },
      ),
    ),

  /** GET admin queue — empty page by default. */
  adminListEmpty: () =>
    http.get("*/api/v1/admin/realty-groups/reports", () =>
      HttpResponse.json({
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 20,
      }),
    ),

  adminListSuccess: <T>(rows: T[]) =>
    http.get("*/api/v1/admin/realty-groups/reports", () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 20,
      }),
    ),

  /** GET admin detail — happy path with an OPEN report. */
  adminDetailSuccess: (overrides: Record<string, unknown> = {}) =>
    http.get("*/api/v1/admin/realty-groups/reports/:publicId", () =>
      HttpResponse.json({
        publicId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
        group: {
          publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
          name: "Sunset Estates",
        },
        reporter: {
          publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
          displayName: "Reporter User",
        },
        reason: "FRAUDULENT_LISTINGS",
        details: "Listings claim regions the group does not actually own.",
        status: "OPEN",
        resolvedByAdmin: null,
        resolvedAt: null,
        resolutionNotes: null,
        createdAt: "2026-05-12T12:00:00Z",
        ...overrides,
      }),
    ),

  /** POST resolve — returns the report as RESOLVED. */
  resolveSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post("*/api/v1/admin/realty-groups/reports/:publicId/resolve", () =>
      HttpResponse.json({
        publicId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
        group: {
          publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
          name: "Sunset Estates",
        },
        reporter: {
          publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
          displayName: "Reporter User",
        },
        reason: "FRAUDULENT_LISTINGS",
        details: "Listings claim regions the group does not actually own.",
        status: "RESOLVED",
        resolvedByAdmin: {
          publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          displayName: "Admin User",
        },
        resolvedAt: "2026-05-12T13:00:00Z",
        resolutionNotes: "Confirmed; suspending group.",
        createdAt: "2026-05-12T12:00:00Z",
        ...overrides,
      }),
    ),

  /** POST dismiss — returns the report as DISMISSED. */
  dismissSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post("*/api/v1/admin/realty-groups/reports/:publicId/dismiss", () =>
      HttpResponse.json({
        publicId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
        group: {
          publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
          name: "Sunset Estates",
        },
        reporter: {
          publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
          displayName: "Reporter User",
        },
        reason: "FRAUDULENT_LISTINGS",
        details: "Listings claim regions the group does not actually own.",
        status: "DISMISSED",
        resolvedByAdmin: {
          publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          displayName: "Admin User",
        },
        resolvedAt: "2026-05-12T13:00:00Z",
        resolutionNotes: "Not actionable.",
        createdAt: "2026-05-12T12:00:00Z",
        ...overrides,
      }),
    ),
};

/**
 * MSW factories for {@code /api/v1/admin/realty-groups/:publicId/listings/*}.
 */
export const bulkSuspendListingsHandlers = {
  suspendAllSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/listings/suspend-all",
      () =>
        HttpResponse.json({
          bulkActionId: "ffffffff-ffff-ffff-ffff-ffffffffffff",
          suspendedCount: 3,
          ...overrides,
        }),
    ),

  suspendAllEmpty: () =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/listings/suspend-all",
      () =>
        HttpResponse.json({
          bulkActionId: "ffffffff-ffff-ffff-ffff-ffffffffffff",
          suspendedCount: 0,
        }),
    ),

  suspendAllForbidden: () =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/listings/suspend-all",
      () =>
        HttpResponse.json(
          { status: 401, title: "Unauthorized" },
          { status: 401 },
        ),
    ),

  reinstateAllSuccess: (count = 3) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/listings/reinstate-all",
      () => HttpResponse.json({ reinstatedCount: count }),
    ),
};

/**
 * MSW factories for {@code /api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId/*}.
 *
 * <p>Recheck has three default outcomes (no-drift, drifted, fetch-failed)
 * so tests can opt into the scenario they want without recomputing the
 * payload from scratch.
 */
export const slGroupAdminHandlers = {
  recheckNoDrift: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId/recheck",
      () =>
        HttpResponse.json({
          driftDetected: false,
          driftReason: null,
          currentFounderUuid: "33333333-3333-3333-3333-333333333333",
          ...overrides,
        }),
    ),

  recheckFounderChanged: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId/recheck",
      () =>
        HttpResponse.json({
          driftDetected: true,
          driftReason: "FOUNDER_CHANGED",
          currentFounderUuid: "44444444-4444-4444-4444-444444444444",
          ...overrides,
        }),
    ),

  recheckFetchFailed: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId/recheck",
      () =>
        HttpResponse.json({
          driftDetected: true,
          driftReason: "FETCH_FAILED_REPEATEDLY",
          currentFounderUuid: null,
          ...overrides,
        }),
    ),

  ackDriftSuccess: (overrides: Record<string, unknown> = {}) =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId/ack-drift",
      () =>
        HttpResponse.json({
          publicId: "11111111-1111-1111-1111-111111111111",
          slGroupUuid: "22222222-2222-2222-2222-222222222222",
          slGroupName: "Sunset Estates",
          verified: true,
          verifiedAt: "2026-05-01T12:00:00Z",
          verifiedVia: "FOUNDER_TERMINAL",
          founderAvatarUuid: "44444444-4444-4444-4444-444444444444",
          currentFounderUuid: "44444444-4444-4444-4444-444444444444",
          lastRevalidatedAt: "2026-05-12T12:00:00Z",
          consecutiveFetchFailures: 0,
          driftDetectedAt: null,
          driftReason: null,
          driftAcknowledgedAt: "2026-05-12T13:00:00Z",
          driftAcknowledgedByAdmin: {
            publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            displayName: "Admin User",
          },
          unregisteredAt: null,
          unregisteredByAdmin: null,
          unregisterReason: null,
          ...overrides,
        }),
    ),

  ackDriftNoDrift: () =>
    http.post(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId/ack-drift",
      () =>
        HttpResponse.json(
          {
            status: 409,
            code: "NO_DRIFT_DETECTED",
            title: "No drift detected",
          },
          { status: 409 },
        ),
    ),

  forceUnregisterSuccess: () =>
    http.delete(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId",
      () => new HttpResponse(null, { status: 204 }),
    ),

  forceUnregisterActiveListings: () =>
    http.delete(
      "*/api/v1/admin/realty-groups/:publicId/sl-groups/:slGroupPublicId",
      () =>
        HttpResponse.json(
          {
            status: 409,
            code: "SL_GROUP_HAS_ACTIVE_LISTINGS",
            title: "Active listings depend on this SL group",
          },
          { status: 409 },
        ),
    ),
};

/**
 * MSW factory for the leader bulk commission-rates PATCH endpoint.
 * Returns 204 on success.
 */
export const bulkCommissionHandlers = {
  updateSuccess: () =>
    http.patch(
      "*/api/v1/realty-groups/:publicId/members/commission-rates",
      () => new HttpResponse(null, { status: 204 }),
    ),

  updateMemberNotInGroup: (memberPublicId: string) =>
    http.patch(
      "*/api/v1/realty-groups/:publicId/members/commission-rates",
      () =>
        HttpResponse.json(
          {
            status: 400,
            code: "MEMBER_NOT_IN_GROUP",
            title: "Member not in group",
            detail: `No member with publicId ${memberPublicId}.`,
          },
          { status: 400 },
        ),
    ),

  updateForbidden: () =>
    http.patch(
      "*/api/v1/realty-groups/:publicId/members/commission-rates",
      () =>
        HttpResponse.json(
          {
            status: 403,
            code: "INSUFFICIENT_GROUP_PERMISSION",
            title: "Insufficient group permission",
          },
          { status: 403 },
        ),
    ),
};

/**
 * MSW factory for the leader commission analytics GET endpoint. The
 * server returns one row per current member (zero-totals for members
 * with no qualifying ledger entries).
 */
export const commissionAnalyticsHandlers = {
  getEmpty: () =>
    http.get(
      "*/api/v1/realty-groups/:publicId/analytics/commissions",
      () => HttpResponse.json([]),
    ),

  getSuccess: <T>(rows: T[]) =>
    http.get(
      "*/api/v1/realty-groups/:publicId/analytics/commissions",
      () => HttpResponse.json(rows),
    ),

  getForbidden: () =>
    http.get(
      "*/api/v1/realty-groups/:publicId/analytics/commissions",
      () =>
        HttpResponse.json(
          {
            status: 403,
            code: "INSUFFICIENT_GROUP_PERMISSION",
            title: "Insufficient group permission",
          },
          { status: 403 },
        ),
    ),
};

/**
 * Default handlers registered at server startup. Establishes the "no session"
 * baseline so tests that don't explicitly authenticate get the unauthenticated
 * bootstrap path automatically.
 *
 * The `me/realty-groups` handler returns an empty array by default so any
 * component that calls {@code useMyRealtyGroups} (e.g. {@code PlaceBidForm}'s
 * COI guard) does not need to register its own handler in tests that don't
 * exercise group membership.
 */
export const defaultHandlers = [
  authHandlers.refreshUnauthenticated(),
  http.get("*/api/v1/me/realty-groups", () => HttpResponse.json([])),
  realtyGroupWalletHandlers.walletEmpty(),
  realtyGroupWalletHandlers.ledgerEmpty(),
  realtyGroupWalletHandlers.withdrawSuccess(),
  realtySlGroupHandlers.listEmpty(),
];
