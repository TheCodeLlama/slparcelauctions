import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  CancellationHistoryDto,
  CancellationStatusResponse,
} from "@/types/cancellation";

/**
 * Read-only client for the seller-scoped cancellation endpoints (Epic 08
 * sub-spec 2 §7.3 / §7.4). Both endpoints require JWT and resolve to the
 * caller — there is no cross-user view, by design.
 *
 * GET /api/v1/users/me/cancellation-status returns the cancel-modal
 * preview envelope ({@code priorOffensesWithBids}, {@code currentSuspension},
 * {@code nextConsequenceIfBidsPresent}).
 *
 * GET /api/v1/users/me/cancellation-history returns a paginated, newest-
 * first list of {@link CancellationHistoryDto} rows. The frontend defaults
 * to {@code size=10} so the dashboard card renders a manageable list and
 * the pager does the rest.
 */

export const DEFAULT_CANCELLATION_HISTORY_SIZE = 10;

/**
 * GET /api/v1/users/me/cancellation-status. Drives the consequence-aware
 * copy in {@code CancelListingModal} and supplies the cancel-modal
 * preview without requiring a second {@code /me} fetch.
 */
export function getCancellationStatus(): Promise<CancellationStatusResponse> {
  return api.get<CancellationStatusResponse>(
    "/api/v1/users/me/cancellation-status",
  );
}

/**
 * GET /api/v1/users/me/cancellation-history?page=&size=. Server returns a
 * standard Spring Data {@link Page} envelope; pagination is exposed
 * through the existing UI {@code <Pagination>} primitive.
 */
export function getCancellationHistory(
  page = 0,
  size = DEFAULT_CANCELLATION_HISTORY_SIZE,
): Promise<Page<CancellationHistoryDto>> {
  return api.get<Page<CancellationHistoryDto>>(
    "/api/v1/users/me/cancellation-history",
    { params: { page, size } },
  );
}
