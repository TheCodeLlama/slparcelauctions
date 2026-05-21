"use client";

import { useMutation } from "@tanstack/react-query";
import { uploadSupportAttachment } from "@/lib/api/support";

/**
 * Mutation wrapper around `POST /api/v1/me/support-tickets/attachments`.
 * Pre-uploads an attachment and returns the opaque `attachmentKey` that
 * the consumer threads into a subsequent create/reply request.
 *
 * No cache invalidation: the staging bucket isn't surfaced anywhere; only
 * the eventual ticket message reflects the upload, and that's invalidated
 * by the create/reply mutation that consumes the key.
 */
export function useUploadSupportAttachment() {
  return useMutation<{ attachmentKey: string }, Error, File>({
    mutationFn: (file) => uploadSupportAttachment(file),
  });
}
