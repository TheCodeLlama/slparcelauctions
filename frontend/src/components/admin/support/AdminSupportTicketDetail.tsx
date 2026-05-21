"use client";

import Link from "next/link";
import {
  useMemo,
  useState,
  type FormEvent,
} from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Checkbox } from "@/components/ui/Checkbox";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Modal } from "@/components/ui/Modal";
import { Textarea } from "@/components/ui/Textarea";
import {
  ChevronLeft,
  EyeOff,
  UserCheck,
  UserMinus,
  UserPlus,
} from "@/components/ui/icons";
import { SupportAttachmentDropzone } from "@/components/support/SupportAttachmentDropzone";
import { useAdminSupportTicket } from "@/hooks/admin/useAdminSupportTicket";
import { useAdminSupportReply } from "@/hooks/admin/useAdminSupportReply";
import { useAdminSupportResolve } from "@/hooks/admin/useAdminSupportResolve";
import { useAdminSupportReopen } from "@/hooks/admin/useAdminSupportReopen";
import { useAdminSupportAssign } from "@/hooks/admin/useAdminSupportAssign";
import { useAdminSupportPatchCategory } from "@/hooks/admin/useAdminSupportPatchCategory";
import { useSignedAttachmentUrl } from "@/hooks/useSignedAttachmentUrl";
import { useAuth } from "@/lib/auth/hooks";
import { isApiError } from "@/lib/api";
import { cn } from "@/lib/cn";
import {
  formatAbsoluteTime,
  formatRelativeTime,
} from "@/lib/time/relativeTime";
import type {
  AdminReplyRequest,
  SupportTicketCategory,
  SupportTicketErrorCode,
  SupportTicketMessageDto,
  SupportTicketStatus,
} from "@/types/support";

const BODY_MAX = 10000;
const ATTACHMENT_MAX = 3;

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  ACCOUNT: "Account",
  BIDDING: "Bidding",
  LISTING: "Listing",
  ESCROW: "Escrow",
  WALLET: "Wallet",
  OTHER: "Other",
};

const CATEGORY_VALUES: SupportTicketCategory[] = [
  "ACCOUNT",
  "BIDDING",
  "LISTING",
  "ESCROW",
  "WALLET",
  "OTHER",
];

function statusPillClass(status: SupportTicketStatus): string {
  if (status === "OPEN") return "bg-warning-bg text-warning";
  return "bg-success-bg text-success";
}

function statusLabel(status: SupportTicketStatus): string {
  return status === "OPEN" ? "Open" : "Resolved";
}

function describeReplyError(err: unknown): string {
  if (!isApiError(err)) {
    return "Could not send reply. Try again.";
  }
  const problem = err.problem as {
    code?: SupportTicketErrorCode;
    detail?: string;
    title?: string;
  };
  switch (problem.code) {
    case "RATE_LIMITED":
      return "You're sending replies too quickly. Wait a moment and try again.";
    case "INVALID_ATTACHMENT":
      return "One of your attachments was rejected. Remove it and try again.";
    case "UNKNOWN_TICKET":
      return "This ticket no longer exists.";
  }
  return problem.detail ?? problem.title ?? "Could not send reply. Try again.";
}

interface MessageBubbleProps {
  message: SupportTicketMessageDto;
  onAttachmentClick: (publicId: string) => void;
}

function MessageBubble({ message, onAttachmentClick }: MessageBubbleProps) {
  const isAdmin = message.authorRole === "ADMIN";
  const isInternalNote = isAdmin && message.visibleToUser === false;
  const sideClasses = isAdmin ? "justify-start" : "justify-end";

  // Three styles on the admin view:
  //   - User bubble (right, brand)
  //   - Admin public reply (left, surface-raised)
  //   - Admin internal note (left, warning palette so it visually pops)
  let bubbleClasses: string;
  if (isInternalNote) {
    bubbleClasses =
      "border border-warning bg-warning-bg/20 text-fg";
  } else if (isAdmin) {
    bubbleClasses = "bg-surface-raised text-fg ring-1 ring-border-subtle";
  } else {
    bubbleClasses = "bg-brand text-white";
  }

  let roleLabel: string;
  if (isInternalNote) roleLabel = "Internal note";
  else if (isAdmin) roleLabel = "Admin";
  else roleLabel = "User";

  return (
    <div
      className={cn("flex w-full", sideClasses)}
      data-testid={`support-message-${message.publicId}`}
      data-role={message.authorRole}
      data-internal={isInternalNote ? "true" : "false"}
    >
      <div className="flex max-w-[80%] flex-col gap-1">
        <div
          className={cn(
            "flex items-center gap-2 text-[11px] text-fg-muted",
            isAdmin ? "justify-start" : "justify-end",
          )}
        >
          {isInternalNote && (
            <EyeOff
              className="size-3 text-warning"
              aria-hidden="true"
              data-testid={`support-message-internal-icon-${message.publicId}`}
            />
          )}
          <span
            className={cn(
              "font-medium",
              isInternalNote ? "text-warning" : "text-fg",
            )}
            data-testid={`support-message-role-${message.publicId}`}
          >
            {roleLabel}
            {message.authorDisplayName ? ` - ${message.authorDisplayName}` : ""}
          </span>
          <span title={formatAbsoluteTime(message.createdAt)}>
            {formatRelativeTime(message.createdAt)}
          </span>
        </div>

        <div
          className={cn(
            "rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap break-words",
            bubbleClasses,
          )}
          data-testid={`support-message-body-${message.publicId}`}
        >
          {message.body}
        </div>

        {message.attachments.length > 0 && (
          <ul
            data-testid={`support-message-attachments-${message.publicId}`}
            className={cn(
              "flex flex-wrap gap-2",
              isAdmin ? "justify-start" : "justify-end",
            )}
          >
            {message.attachments.map((att) => (
              <li key={att.publicId}>
                <button
                  type="button"
                  onClick={() => onAttachmentClick(att.publicId)}
                  data-testid={`support-attachment-${att.publicId}`}
                  aria-label="View attachment"
                  className="inline-flex size-20 items-center justify-center rounded-lg bg-bg-subtle ring-1 ring-border-subtle text-[10px] text-fg-muted hover:ring-brand focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                >
                  Attachment
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

interface AttachmentLightboxProps {
  attachmentPublicId: string | null;
  onClose: () => void;
}

function AttachmentLightbox({
  attachmentPublicId,
  onClose,
}: AttachmentLightboxProps) {
  const { data, isPending, isError } = useSignedAttachmentUrl(
    attachmentPublicId,
  );

  return (
    <Modal
      open={attachmentPublicId !== null}
      title="Attachment"
      onClose={onClose}
    >
      <div
        className="flex min-h-[12rem] items-center justify-center"
        data-testid="support-attachment-lightbox"
      >
        {isPending && <LoadingSpinner label="Loading image..." />}
        {isError && (
          <p className="text-sm text-danger" role="alert">
            Could not load attachment.
          </p>
        )}
        {data?.url && (
          // Signed S3 URL is absolute (presigned); skip apiUrl() and JWT.
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={data.url}
            alt="Support ticket attachment"
            className="max-h-[70vh] w-auto max-w-full rounded-lg"
            data-testid="support-attachment-lightbox-image"
          />
        )}
      </div>
    </Modal>
  );
}

interface AdminSupportTicketDetailProps {
  publicId: string;
}

export function AdminSupportTicketDetail({
  publicId,
}: AdminSupportTicketDetailProps) {
  const session = useAuth();
  const callerAdminPublicId =
    session.status === "authenticated" ? session.user.publicId : null;

  const ticketQuery = useAdminSupportTicket(publicId);
  const replyMutation = useAdminSupportReply(publicId);
  const resolveMutation = useAdminSupportResolve(publicId);
  const reopenMutation = useAdminSupportReopen(publicId);
  const assignMutation = useAdminSupportAssign(publicId);
  const patchCategoryMutation = useAdminSupportPatchCategory(publicId);

  const [replyBody, setReplyBody] = useState("");
  const [replyAttachmentKeys, setReplyAttachmentKeys] = useState<string[]>([]);
  const [internalNote, setInternalNote] = useState(false);
  const [replyError, setReplyError] = useState<string | null>(null);
  const [lightboxAttachmentId, setLightboxAttachmentId] = useState<
    string | null
  >(null);

  // Admin sees every message, including internal notes. Defensive: the
  // backend's admin mapper already returns the full list, but we don't
  // re-filter here either way.
  const allMessages: SupportTicketMessageDto[] = useMemo(
    () => ticketQuery.data?.messages ?? [],
    [ticketQuery.data?.messages],
  );

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = replyBody.trim();
    if (trimmed.length === 0) {
      setReplyError("Reply cannot be empty.");
      return;
    }
    if (trimmed.length > BODY_MAX) {
      setReplyError(`Reply must be ${BODY_MAX} characters or fewer.`);
      return;
    }
    setReplyError(null);
    const req: AdminReplyRequest = {
      body: trimmed,
      attachmentKeys:
        replyAttachmentKeys.length === 0 ? undefined : replyAttachmentKeys,
      internalNote,
    };
    replyMutation.mutate(req, {
      onSuccess: () => {
        setReplyBody("");
        setReplyAttachmentKeys([]);
        setInternalNote(false);
      },
    });
  }

  if (ticketQuery.isLoading) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <LoadingSpinner label="Loading ticket..." />
      </div>
    );
  }

  if (ticketQuery.isError || !ticketQuery.data) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <div
          role="alert"
          className="rounded-lg border border-danger/30 bg-danger-bg/50 px-4 py-3 text-sm text-danger"
          data-testid="admin-support-detail-load-error"
        >
          Could not load this ticket. It may have been removed.
        </div>
        <div className="mt-4">
          <Link
            href="/admin/support"
            className="text-xs text-fg-muted hover:text-fg inline-flex items-center gap-1"
          >
            <ChevronLeft className="size-3" aria-hidden="true" />
            Back to support queue
          </Link>
        </div>
      </div>
    );
  }

  const ticket = ticketQuery.data;
  const isResolved = ticket.status === "RESOLVED";
  const submitting = replyMutation.isPending;
  const serverError =
    replyMutation.isError && !replyError
      ? describeReplyError(replyMutation.error)
      : null;

  const assignedToCaller =
    !!callerAdminPublicId &&
    ticket.assignedAdminPublicId === callerAdminPublicId;

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 flex flex-col gap-6">
      <div>
        <Link
          href="/admin/support"
          className="text-xs text-fg-muted hover:text-fg inline-flex items-center gap-1"
          data-testid="admin-support-detail-back-link"
        >
          <ChevronLeft className="size-3" aria-hidden="true" />
          Back to support queue
        </Link>
      </div>

      <Card>
        <Card.Header>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex flex-col gap-2 min-w-0">
              <h1
                className="text-xl font-bold tracking-tight font-display break-words"
                data-testid="admin-support-detail-subject"
              >
                {ticket.subject}
              </h1>
              <div className="flex flex-wrap items-center gap-2">
                <label className="flex items-center gap-2 text-xs text-fg-muted">
                  Category
                  <select
                    value={ticket.category}
                    onChange={(e) =>
                      patchCategoryMutation.mutate(
                        e.target.value as SupportTicketCategory,
                      )
                    }
                    disabled={patchCategoryMutation.isPending}
                    data-testid="admin-support-detail-category-select"
                    aria-label="Change ticket category"
                    className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
                  >
                    {CATEGORY_VALUES.map((value) => (
                      <option key={value} value={value}>
                        {CATEGORY_LABELS[value]}
                      </option>
                    ))}
                  </select>
                </label>
                <span
                  className={cn(
                    "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                    statusPillClass(ticket.status),
                  )}
                  data-testid="admin-support-detail-status-pill"
                >
                  {statusLabel(ticket.status)}
                </span>
              </div>
              <div
                className="text-[11px] text-fg-muted"
                data-testid="admin-support-detail-assignee"
              >
                Submitter:{" "}
                <span className="text-fg">{ticket.submitterDisplayName}</span>
                {"  -  "}
                Assigned to:{" "}
                <span className="text-fg">
                  {ticket.assignedAdminDisplayName ?? "Unassigned"}
                </span>
              </div>
            </div>
            <div
              className="text-[11px] text-fg-muted"
              title={formatAbsoluteTime(ticket.updatedAt)}
            >
              Updated {formatRelativeTime(ticket.updatedAt)}
            </div>
          </div>
        </Card.Header>
        <Card.Body>
          <div className="flex flex-wrap items-center gap-2">
            {!assignedToCaller && callerAdminPublicId && (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() =>
                  assignMutation.mutate(callerAdminPublicId)
                }
                disabled={assignMutation.isPending}
                leftIcon={<UserPlus className="size-3.5" />}
                data-testid="admin-support-detail-assign-me"
              >
                Assign to me
              </Button>
            )}
            {ticket.assignedAdminPublicId !== null && (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => assignMutation.mutate(null)}
                disabled={assignMutation.isPending}
                leftIcon={<UserMinus className="size-3.5" />}
                data-testid="admin-support-detail-unassign"
              >
                Unassign
              </Button>
            )}
            {!isResolved && (
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={() => resolveMutation.mutate()}
                disabled={resolveMutation.isPending}
                leftIcon={<UserCheck className="size-3.5" />}
                data-testid="admin-support-detail-resolve"
              >
                Resolve
              </Button>
            )}
            {isResolved && (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => reopenMutation.mutate()}
                disabled={reopenMutation.isPending}
                data-testid="admin-support-detail-reopen"
              >
                Reopen
              </Button>
            )}
          </div>
        </Card.Body>
      </Card>

      <div
        className="flex flex-col gap-4"
        data-testid="admin-support-detail-messages"
      >
        {allMessages.map((message) => (
          <MessageBubble
            key={message.publicId}
            message={message}
            onAttachmentClick={setLightboxAttachmentId}
          />
        ))}
      </div>

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Reply
          </h2>
        </Card.Header>
        <Card.Body>
          <form
            onSubmit={handleSubmit}
            className="flex flex-col gap-3"
            aria-label="Reply to support ticket"
            data-testid="admin-support-detail-reply-form"
            noValidate
          >
            <div
              data-testid="admin-support-detail-composer-wrapper"
              data-internal={internalNote ? "true" : "false"}
              className={cn(
                "rounded-lg p-3 transition-colors",
                internalNote
                  ? "border border-warning bg-warning-bg/20"
                  : "border border-transparent bg-transparent",
              )}
            >
              <Textarea
                label={internalNote ? "Internal note" : "Public reply"}
                value={replyBody}
                onChange={(e) => {
                  setReplyBody(e.target.value);
                  if (replyError) setReplyError(null);
                }}
                placeholder={
                  internalNote
                    ? "Type a note visible only to admins..."
                    : "Type your reply..."
                }
                rows={5}
                maxLength={BODY_MAX}
                data-testid="admin-support-detail-reply-textarea"
                error={replyError ?? undefined}
                disabled={submitting}
              />
            </div>

            <SupportAttachmentDropzone
              attachmentKeys={replyAttachmentKeys}
              onAttachmentKeyAdded={(key) =>
                setReplyAttachmentKeys((prev) =>
                  prev.includes(key) ? prev : [...prev, key],
                )
              }
              onAttachmentKeyRemoved={(key) =>
                setReplyAttachmentKeys((prev) => prev.filter((k) => k !== key))
              }
              maxAttachments={ATTACHMENT_MAX}
              disabled={submitting}
            />

            <Checkbox
              checked={internalNote}
              onChange={(e) => setInternalNote(e.target.checked)}
              disabled={submitting}
              data-testid="admin-support-detail-internal-note-checkbox"
              label={
                <span className="inline-flex items-center gap-1.5">
                  <EyeOff
                    className="size-3 text-warning"
                    aria-hidden="true"
                  />
                  Internal note (not visible to the user)
                </span>
              }
            />

            {serverError && (
              <div
                role="alert"
                data-testid="admin-support-detail-reply-error"
                className="rounded-lg border border-danger/30 bg-danger-bg/50 px-4 py-3 text-sm text-danger"
              >
                {serverError}
              </div>
            )}

            <div className="flex justify-end">
              <Button
                type="submit"
                variant="primary"
                loading={submitting}
                disabled={submitting}
                data-testid="admin-support-detail-reply-submit"
              >
                {internalNote ? "Add internal note" : "Send reply"}
              </Button>
            </div>
          </form>
        </Card.Body>
      </Card>

      <AttachmentLightbox
        attachmentPublicId={lightboxAttachmentId}
        onClose={() => setLightboxAttachmentId(null)}
      />
    </div>
  );
}
