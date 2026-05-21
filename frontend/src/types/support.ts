// Frontend types for the customer-support-contact feature.
// Mirrors backend DTOs in com.slparcelauctions.backend.support.dto and the
// admin queue DTOs in com.slparcelauctions.backend.admin.support.dto.
//
// `publicId` is the only identifier that crosses the wire; numeric ids stay
// server-side (see CLAUDE.md "BaseEntity convention").

export type SupportTicketStatus = "OPEN" | "RESOLVED";

export type SupportTicketCategory =
  | "ACCOUNT"
  | "BIDDING"
  | "LISTING"
  | "ESCROW"
  | "WALLET"
  | "OTHER";

export type SupportTicketAuthorRole = "USER" | "ADMIN";

export interface SupportTicketAttachmentDto {
  publicId: string;
  mimeType: string;
  sizeBytes: number;
  width: number | null;
  height: number | null;
}

export interface SupportTicketMessageDto {
  publicId: string;
  authorPublicId: string;
  authorDisplayName: string;
  authorRole: SupportTicketAuthorRole;
  body: string;
  visibleToUser: boolean;
  createdAt: string;
  attachments: SupportTicketAttachmentDto[];
}

export interface SupportTicketDto {
  publicId: string;
  submitterPublicId: string;
  submitterDisplayName: string;
  subject: string;
  category: SupportTicketCategory;
  status: SupportTicketStatus;
  assignedAdminPublicId: string | null;
  assignedAdminDisplayName: string | null;
  lastMessageAt: string;
  lastMessageAuthor: SupportTicketAuthorRole;
  resolvedAt: string | null;
  createdAt: string;
  updatedAt: string;
  messages: SupportTicketMessageDto[];
}

export interface SupportTicketSummaryDto {
  publicId: string;
  subject: string;
  category: SupportTicketCategory;
  status: SupportTicketStatus;
  lastMessageAuthor: SupportTicketAuthorRole;
  lastMessageAt: string;
}

export interface AdminSupportTicketQueueRow {
  publicId: string;
  subject: string;
  category: SupportTicketCategory;
  status: SupportTicketStatus;
  submitterPublicId: string;
  submitterDisplayName: string;
  assignedAdminPublicId: string | null;
  assignedAdminDisplayName: string | null;
  lastMessageAuthor: SupportTicketAuthorRole;
  lastMessageAt: string;
}

export interface SupportTicketQueueStatsDto {
  openNeedingAdminReply: number;
  openTotal: number;
}

/**
 * Problem-detail `code` values returned by SupportTicketException via the
 * shared GlobalExceptionHandler. Callers narrow `error.problem.code` to
 * decide between inline form errors and toast surfaces.
 */
export type SupportTicketErrorCode =
  | "UNKNOWN_TICKET"
  | "NOT_OWNER"
  | "INTERNAL_NOTE_FROM_USER"
  | "RATE_LIMITED"
  | "INVALID_ATTACHMENT"
  | "INVALID_CATEGORY"
  | "ATTACHMENT_NOT_FOUND";

export interface CreateSupportTicketRequest {
  subject: string;
  category: SupportTicketCategory;
  body: string;
  attachmentKeys?: string[];
}

export interface ReplySupportTicketRequest {
  body: string;
  attachmentKeys?: string[];
}

export interface AdminReplyRequest {
  body: string;
  attachmentKeys?: string[];
  internalNote?: boolean;
}
