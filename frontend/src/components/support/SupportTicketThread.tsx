"use client";

import Link from "next/link";
import {
  useMemo,
  useState,
  type FormEvent,
} from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Modal } from "@/components/ui/Modal";
import { Textarea } from "@/components/ui/Textarea";
import { ChevronLeft } from "@/components/ui/icons";
import { SupportAttachmentDropzone } from "@/components/support/SupportAttachmentDropzone";
import { useMySupportTicket } from "@/hooks/useMySupportTicket";
import { useReplySupportTicket } from "@/hooks/useReplySupportTicket";
import { useSignedAttachmentUrl } from "@/hooks/useSignedAttachmentUrl";
import { isApiError } from "@/lib/api";
import { cn } from "@/lib/cn";
import {
  formatAbsoluteTime,
  formatRelativeTime,
} from "@/lib/time/relativeTime";
import type {
  ReplySupportTicketRequest,
  SupportTicketCategory,
  SupportTicketErrorCode,
  SupportTicketMessageDto,
  SupportTicketStatus,
} from "@/types/support";

const BODY_MAX = 10000;

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  ACCOUNT: "Account",
  BIDDING: "Bidding",
  LISTING: "Listing",
  ESCROW: "Escrow",
  WALLET: "Wallet",
  OTHER: "Other",
};

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
    case "NOT_OWNER":
      return "You can't reply on this ticket.";
  }
  return problem.detail ?? problem.title ?? "Could not send reply. Try again.";
}

interface MessageBubbleProps {
  message: SupportTicketMessageDto;
  onAttachmentClick: (publicId: string) => void;
}

function MessageBubble({ message, onAttachmentClick }: MessageBubbleProps) {
  const isAdmin = message.authorRole === "ADMIN";
  const sideClasses = isAdmin ? "justify-start" : "justify-end";
  // Admin = surface-raised left, user = brand-bg right. Brand white-on-brand
  // matches the project's `Button variant="primary"` treatment (see
  // `components/ui/Button.tsx`).
  const bubbleClasses = isAdmin
    ? "bg-surface-raised text-fg ring-1 ring-border-subtle"
    : "bg-brand text-white";
  const roleLabel = isAdmin ? "Admin" : "You";

  return (
    <div
      className={cn("flex w-full", sideClasses)}
      data-testid={`support-message-${message.publicId}`}
      data-role={message.authorRole}
    >
      <div className="flex max-w-[80%] flex-col gap-1">
        <div
          className={cn(
            "flex items-center gap-2 text-[11px] text-fg-muted",
            isAdmin ? "justify-start" : "justify-end",
          )}
        >
          <span className="font-medium text-fg">
            {roleLabel}
            {message.authorDisplayName ? ` · ${message.authorDisplayName}` : ""}
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
  // The hook stays disabled when publicId is null, so this is safe to render
  // unconditionally — there's no fetch on initial thread render.
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
          // Signed S3 URL is absolute (presigned), so it does NOT need to
          // flow through apiUrl(). It also doesn't need the JWT — the
          // presigned URL is the auth.
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

interface SupportTicketThreadProps {
  publicId: string;
}

export function SupportTicketThread({ publicId }: SupportTicketThreadProps) {
  const ticketQuery = useMySupportTicket(publicId);
  const replyMutation = useReplySupportTicket(publicId);

  const [replyBody, setReplyBody] = useState("");
  const [replyAttachmentKeys, setReplyAttachmentKeys] = useState<string[]>([]);
  const [replyError, setReplyError] = useState<string | null>(null);
  const [lightboxAttachmentId, setLightboxAttachmentId] = useState<
    string | null
  >(null);

  // The backend mapper already drops internal notes from the /me/ DTO, but
  // we defend in depth — a future regression that ever included them
  // shouldn't leak admin-only context to the seller.
  const visibleMessages: SupportTicketMessageDto[] = useMemo(() => {
    const all = ticketQuery.data?.messages ?? [];
    return all.filter((m) => m.visibleToUser !== false);
  }, [ticketQuery.data?.messages]);

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
    const req: ReplySupportTicketRequest = {
      body: trimmed,
      attachmentKeys:
        replyAttachmentKeys.length === 0 ? undefined : replyAttachmentKeys,
    };
    replyMutation.mutate(req, {
      onSuccess: () => {
        setReplyBody("");
        setReplyAttachmentKeys([]);
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
          data-testid="support-ticket-load-error"
        >
          Could not load this ticket. It may have been removed, or you may
          not have access.
        </div>
        <div className="mt-4">
          <Link
            href="/support"
            className="text-xs text-fg-muted hover:text-fg inline-flex items-center gap-1"
          >
            <ChevronLeft className="size-3" aria-hidden="true" />
            Back to support
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

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 flex flex-col gap-6">
      <div>
        <Link
          href="/support"
          className="text-xs text-fg-muted hover:text-fg inline-flex items-center gap-1"
          data-testid="support-thread-back-link"
        >
          <ChevronLeft className="size-3" aria-hidden="true" />
          Back to support
        </Link>
      </div>

      <Card>
        <Card.Header>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex flex-col gap-2 min-w-0">
              <h1
                className="text-xl font-bold tracking-tight font-display break-words"
                data-testid="support-thread-subject"
              >
                {ticket.subject}
              </h1>
              <div className="flex flex-wrap items-center gap-2">
                <span
                  className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-medium bg-info-bg text-info"
                  data-testid="support-thread-category"
                >
                  {CATEGORY_LABELS[ticket.category]}
                </span>
                <span
                  className={cn(
                    "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                    statusPillClass(ticket.status),
                  )}
                  data-testid="support-thread-status"
                >
                  {statusLabel(ticket.status)}
                </span>
              </div>
            </div>
            <div
              className="text-[11px] text-fg-muted"
              title={formatAbsoluteTime(ticket.updatedAt)}
              data-testid="support-thread-updated"
            >
              Updated {formatRelativeTime(ticket.updatedAt)}
            </div>
          </div>
        </Card.Header>
      </Card>

      <div
        className="flex flex-col gap-4"
        data-testid="support-thread-messages"
      >
        {visibleMessages.map((message) => (
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
            data-testid="support-thread-reply-form"
            noValidate
          >
            {isResolved && (
              <p
                className="rounded-lg bg-info-bg/60 px-3 py-2 text-xs text-info"
                data-testid="support-thread-reopen-note"
              >
                Replying will reopen this ticket.
              </p>
            )}

            <Textarea
              label="Your reply"
              value={replyBody}
              onChange={(e) => {
                setReplyBody(e.target.value);
                if (replyError) setReplyError(null);
              }}
              placeholder="Type your reply..."
              rows={5}
              data-testid="support-thread-reply-textarea"
              error={replyError ?? undefined}
              disabled={submitting}
            />

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
              disabled={submitting}
            />

            {serverError && (
              <div
                role="alert"
                data-testid="support-thread-reply-error"
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
                data-testid="support-thread-reply-submit"
              >
                Send reply
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
