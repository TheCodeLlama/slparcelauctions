import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AdminDismissReportRequest,
  AdminRealtyGroupReportDetail,
  AdminRealtyGroupReportRow,
  AdminRealtyGroupReportsFilters,
  AdminResolveReportRequest,
  RealtyGroupReport,
  SubmitReportRequest,
} from "@/types/realty";

/**
 * Realty Groups: F — Report API clients.
 *
 * <p>Split across two surfaces:
 * <ul>
 *   <li>{@link realtyGroupReportsApi} — public submit endpoint (any
 *       authenticated non-member user can report a group).</li>
 *   <li>{@link adminRealtyGroupReportsApi} — admin queue + detail + resolve
 *       / dismiss actions. Requires {@code ROLE_ADMIN}.</li>
 * </ul>
 *
 * <p>Backend: {@code RealtyGroupReportController} (public) +
 * {@code AdminRealtyGroupReportController} (admin).
 */
export const realtyGroupReportsApi = {
  /**
   * Submit a report against a realty group. Returns the persisted row's
   * narrow wire-shape with a 201. Failure modes are surfaced via
   * {@code ApiError} — 404 group-not-found, 409 already-reported,
   * 409 own-group, 429 rate-limited.
   */
  submit(
    groupPublicId: string,
    body: SubmitReportRequest,
  ): Promise<RealtyGroupReport> {
    return api.post<RealtyGroupReport>(
      `/api/v1/realty-groups/${groupPublicId}/reports`,
      body,
    );
  },
};

/**
 * Admin-only report triage surface. List, detail, resolve, dismiss. Mirrors
 * the four-endpoint backend controller.
 */
export const adminRealtyGroupReportsApi = {
  /**
   * Paginated admin queue. Default sort is {@code createdAt DESC}; status
   * filter is optional (server returns all statuses when omitted).
   */
  list(
    filters: AdminRealtyGroupReportsFilters,
  ): Promise<Page<AdminRealtyGroupReportRow>> {
    const search = new URLSearchParams();
    if (filters.status) search.set("status", filters.status);
    search.set("page", String(filters.page));
    search.set("size", String(filters.size));
    if (filters.sort) search.set("sort", filters.sort);
    return api.get<Page<AdminRealtyGroupReportRow>>(
      `/api/v1/admin/realty-groups/reports?${search.toString()}`,
    );
  },

  /**
   * Per-group narrow list — used by the group detail page's Reports tab.
   * Filters server-side by {@code groupPublicId} to avoid hauling the
   * whole queue across the wire.
   */
  listByGroup(
    groupPublicId: string,
    filters: Omit<AdminRealtyGroupReportsFilters, "page" | "size"> & {
      page?: number;
      size?: number;
    } = {},
  ): Promise<Page<AdminRealtyGroupReportRow>> {
    const search = new URLSearchParams();
    search.set("groupPublicId", groupPublicId);
    if (filters.status) search.set("status", filters.status);
    search.set("page", String(filters.page ?? 0));
    search.set("size", String(filters.size ?? 20));
    if (filters.sort) search.set("sort", filters.sort);
    return api.get<Page<AdminRealtyGroupReportRow>>(
      `/api/v1/admin/realty-groups/reports?${search.toString()}`,
    );
  },

  /** Fetch the full detail for a single report row. */
  detail(reportPublicId: string): Promise<AdminRealtyGroupReportDetail> {
    return api.get<AdminRealtyGroupReportDetail>(
      `/api/v1/admin/realty-groups/reports/${reportPublicId}`,
    );
  },

  /**
   * Mark report as RESOLVED. {@code escalateTo} is informational only;
   * the backend never acts on it — the frontend uses it to chain into a
   * suspension modal post-resolve.
   */
  resolve(
    reportPublicId: string,
    body: AdminResolveReportRequest,
  ): Promise<AdminRealtyGroupReportDetail> {
    return api.post<AdminRealtyGroupReportDetail>(
      `/api/v1/admin/realty-groups/reports/${reportPublicId}/resolve`,
      body,
    );
  },

  /**
   * Mark report as DISMISSED. Bumps the reporter's
   * {@code dismissedReportsCount} server-side.
   */
  dismiss(
    reportPublicId: string,
    body: AdminDismissReportRequest,
  ): Promise<AdminRealtyGroupReportDetail> {
    return api.post<AdminRealtyGroupReportDetail>(
      `/api/v1/admin/realty-groups/reports/${reportPublicId}/dismiss`,
      body,
    );
  },
};
