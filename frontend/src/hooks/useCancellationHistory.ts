"use client";

import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import {
  DEFAULT_CANCELLATION_HISTORY_SIZE,
  getCancellationHistory,
} from "@/lib/api/cancellations";
import type { CancellationHistoryDto } from "@/types/cancellation";
import type { Page } from "@/types/page";
import { cancellationKeys } from "./useCancellationStatus";

/**
 * Paginated read hook for the seller's cancellation history. Default page
 * size matches the API client default ({@link DEFAULT_CANCELLATION_HISTORY_SIZE}).
 *
 * <p>The query key is page+size scoped so flipping pages caches both
 * pages independently — pager clicks feel instant on a return visit.
 */
export function useCancellationHistory(
  page: number,
  size: number = DEFAULT_CANCELLATION_HISTORY_SIZE,
): UseQueryResult<Page<CancellationHistoryDto>> {
  return useQuery<Page<CancellationHistoryDto>>({
    queryKey: cancellationKeys.history(page, size),
    queryFn: () => getCancellationHistory(page, size),
  });
}
