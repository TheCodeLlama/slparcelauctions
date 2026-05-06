"use client";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";
import type {
  AdminListingActionRequest,
  AdminListingsFilters,
} from "@/lib/admin/types";

export function useAdminListingsList(filters: AdminListingsFilters) {
  return useQuery({
    queryKey: adminQueryKeys.listingsList(filters),
    queryFn: () => adminApi.listings.list(filters),
    staleTime: 5_000,
  });
}

export function adminListingErrorMessage(err: unknown, fallback: string): string {
  if (!isApiError(err)) return fallback;
  const code = (err.problem as { code?: string }).code;
  switch (code) {
    case "INVALID_STATUS_FOR_ACTION":
      return "This listing's status changed — refresh and try again.";
    case "ALREADY_SUSPENDED":
      return "This listing is already suspended.";
    case "NOT_SUSPENDED":
      return "This listing isn't suspended.";
    case "LISTING_NOT_FOUND":
      return "Listing not found. It may have been deleted.";
    case "INVALID_SORT_COLUMN":
      return "Invalid sort column. Reset sort and try again.";
    default:
      return fallback;
  }
}

function makeInvalidator(qc: ReturnType<typeof useQueryClient>) {
  return () => {
    qc.invalidateQueries({ queryKey: adminQueryKeys.listings() });
  };
}

export function useWarnListing() {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc);
  return useMutation({
    mutationFn: ({ publicId, body }: { publicId: string; body: AdminListingActionRequest }) =>
      adminApi.listings.warn(publicId, body),
    onSuccess: () => {
      invalidate();
      toast.success("Warning sent to seller.");
    },
    onError: (e) => toast.error(adminListingErrorMessage(e, "Couldn't send warning.")),
  });
}

export function useSuspendListing() {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc);
  return useMutation({
    mutationFn: ({ publicId, body }: { publicId: string; body: AdminListingActionRequest }) =>
      adminApi.listings.suspend(publicId, body),
    onSuccess: () => {
      invalidate();
      toast.success("Listing suspended.");
    },
    onError: (e) => toast.error(adminListingErrorMessage(e, "Couldn't suspend listing.")),
  });
}

export function useCancelListing() {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc);
  return useMutation({
    mutationFn: ({ publicId, body }: { publicId: string; body: AdminListingActionRequest }) =>
      adminApi.listings.cancel(publicId, body),
    onSuccess: () => {
      invalidate();
      toast.success("Listing cancelled.");
    },
    onError: (e) => toast.error(adminListingErrorMessage(e, "Couldn't cancel listing.")),
  });
}

export function useReinstateListing() {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc);
  return useMutation({
    mutationFn: ({ publicId, body }: { publicId: string; body: AdminListingActionRequest }) =>
      adminApi.listings.reinstate(publicId, body),
    onSuccess: () => {
      invalidate();
      toast.success("Listing reinstated.");
    },
    onError: (e) => toast.error(adminListingErrorMessage(e, "Couldn't reinstate listing.")),
  });
}
