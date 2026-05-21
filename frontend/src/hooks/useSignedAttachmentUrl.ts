"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchAttachmentSignedUrl } from "@/lib/api/support";

export const signedAttachmentUrlKey = (publicId: string) =>
  ["support-attachment-signed-url", publicId] as const;

/**
 * Fetches a short-lived signed URL for a support-ticket attachment. The
 * backend issues 5-minute presigned S3 GETs; we stale at 4 minutes so a
 * tab left open refreshes the URL just before expiry.
 *
 * `publicId` is nullable so consumers can conditionally render an
 * attachment thumbnail without an extra guard — when `null` the query
 * stays disabled.
 */
export function useSignedAttachmentUrl(publicId: string | null) {
  return useQuery<{ url: string }>({
    queryKey: signedAttachmentUrlKey(publicId ?? ""),
    queryFn: () => fetchAttachmentSignedUrl(publicId!),
    enabled: !!publicId,
    staleTime: 4 * 60 * 1000,
  });
}
