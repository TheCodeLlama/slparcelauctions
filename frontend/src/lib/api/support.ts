import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AdminReplyRequest,
  AdminSupportTicketQueueRow,
  CreateSupportTicketRequest,
  ReplySupportTicketRequest,
  SupportTicketCategory,
  SupportTicketDto,
  SupportTicketMessageDto,
  SupportTicketQueueStatsDto,
  SupportTicketStatus,
  SupportTicketSummaryDto,
} from "@/types/support";

/**
 * Customer-support API client. Wraps the user-facing endpoints under
 * `/api/v1/me/support-tickets` and the admin queue endpoints under
 * `/api/v1/admin/support-tickets`. The signed-URL endpoint is shared
 * between both surfaces.
 *
 * Backend authoritative source: spec
 * `docs/superpowers/specs/2026-05-20-customer-support-contact-design.md`.
 */

// ─── User-facing list params ─────────────────────────────────────────────

export interface MyListParams {
  status?: SupportTicketStatus;
  q?: string;
  page?: number;
  size?: number;
}

export function fetchMySupportTickets(
  params: MyListParams = {},
): Promise<Page<SupportTicketSummaryDto>> {
  return api.get<Page<SupportTicketSummaryDto>>(
    "/api/v1/me/support-tickets",
    { params: params as Record<string, string | number | undefined> },
  );
}

export function fetchMySupportTicket(
  publicId: string,
): Promise<SupportTicketDto> {
  return api.get<SupportTicketDto>(`/api/v1/me/support-tickets/${publicId}`);
}

export function createSupportTicket(
  req: CreateSupportTicketRequest,
): Promise<SupportTicketDto> {
  return api.post<SupportTicketDto>("/api/v1/me/support-tickets", req);
}

export function replySupportTicket(
  publicId: string,
  req: ReplySupportTicketRequest,
): Promise<SupportTicketMessageDto> {
  return api.post<SupportTicketMessageDto>(
    `/api/v1/me/support-tickets/${publicId}/messages`,
    req,
  );
}

/**
 * Multipart upload of a single attachment file. The shared `api.post`
 * helper detects FormData and omits the Content-Type header so the browser
 * can set the multipart boundary itself.
 */
export function uploadSupportAttachment(
  file: File,
): Promise<{ attachmentKey: string }> {
  const fd = new FormData();
  fd.append("file", file);
  return api.post<{ attachmentKey: string }>(
    "/api/v1/me/support-tickets/attachments",
    fd,
  );
}

export function fetchAttachmentSignedUrl(
  publicId: string,
): Promise<{ url: string }> {
  return api.get<{ url: string }>(
    `/api/v1/support-tickets/attachments/${publicId}`,
  );
}

// ─── Admin queue ──────────────────────────────────────────────────────────

export interface AdminListParams {
  status?: SupportTicketStatus;
  category?: SupportTicketCategory;
  assignee?: string;
  last_author?: "USER" | "ADMIN";
  q?: string;
  page?: number;
  size?: number;
}

export function fetchAdminSupportTickets(
  params: AdminListParams = {},
): Promise<Page<AdminSupportTicketQueueRow>> {
  return api.get<Page<AdminSupportTicketQueueRow>>(
    "/api/v1/admin/support-tickets",
    { params: params as Record<string, string | number | undefined> },
  );
}

export function fetchAdminSupportTicket(
  publicId: string,
): Promise<SupportTicketDto> {
  return api.get<SupportTicketDto>(
    `/api/v1/admin/support-tickets/${publicId}`,
  );
}

export function fetchAdminSupportQueueStats(): Promise<SupportTicketQueueStatsDto> {
  return api.get<SupportTicketQueueStatsDto>(
    "/api/v1/admin/support-tickets/queue-stats",
  );
}

export function adminReplySupportTicket(
  publicId: string,
  req: AdminReplyRequest,
): Promise<SupportTicketMessageDto> {
  return api.post<SupportTicketMessageDto>(
    `/api/v1/admin/support-tickets/${publicId}/messages`,
    req,
  );
}

export function adminResolveSupportTicket(
  publicId: string,
): Promise<SupportTicketDto> {
  return api.post<SupportTicketDto>(
    `/api/v1/admin/support-tickets/${publicId}/resolve`,
    {},
  );
}

export function adminReopenSupportTicket(
  publicId: string,
): Promise<SupportTicketDto> {
  return api.post<SupportTicketDto>(
    `/api/v1/admin/support-tickets/${publicId}/reopen`,
    {},
  );
}

export function adminAssignSupportTicket(
  publicId: string,
  adminPublicId: string | null,
): Promise<SupportTicketDto> {
  return api.post<SupportTicketDto>(
    `/api/v1/admin/support-tickets/${publicId}/assign`,
    { adminPublicId },
  );
}

export function adminPatchSupportTicket(
  publicId: string,
  category: SupportTicketCategory,
): Promise<SupportTicketDto> {
  return api.patch<SupportTicketDto>(
    `/api/v1/admin/support-tickets/${publicId}`,
    { category },
  );
}
