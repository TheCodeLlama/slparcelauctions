"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { Textarea } from "@/components/ui/Textarea";
import { SupportAttachmentDropzone } from "@/components/support/SupportAttachmentDropzone";
import { useCreateSupportTicket } from "@/hooks/useCreateSupportTicket";
import { isApiError } from "@/lib/api";
import type {
  CreateSupportTicketRequest,
  SupportTicketCategory,
  SupportTicketErrorCode,
} from "@/types/support";

const SUBJECT_MAX = 160;
const BODY_MAX = 10000;

const CATEGORY_OPTIONS: Array<{ value: SupportTicketCategory; label: string }> = [
  { value: "ACCOUNT", label: "Account" },
  { value: "BIDDING", label: "Bidding" },
  { value: "LISTING", label: "Listing" },
  { value: "ESCROW", label: "Escrow" },
  { value: "WALLET", label: "Wallet" },
  { value: "OTHER", label: "Other" },
];

interface SectionProps {
  title: string;
  description?: ReactNode;
  children: ReactNode;
}

function Section({ title, description, children }: SectionProps) {
  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight text-fg">{title}</h2>
        {description && (
          <p className="mt-1 text-xs text-fg-muted">{description}</p>
        )}
      </Card.Header>
      <Card.Body>
        <div className="flex flex-col gap-4">{children}</div>
      </Card.Body>
    </Card>
  );
}

function describeError(err: unknown): string {
  if (!isApiError(err)) {
    return "Could not submit ticket. Try again.";
  }
  const problem = err.problem as {
    code?: SupportTicketErrorCode;
    detail?: string;
    title?: string;
  };
  switch (problem.code) {
    case "RATE_LIMITED":
      return "You're sending too many tickets. Wait an hour or reply to an existing open ticket.";
    case "INVALID_ATTACHMENT":
      return "One of your attachments was rejected. Remove it and try again.";
  }
  return problem.detail ?? problem.title ?? "Could not submit ticket. Try again.";
}

export function NewSupportTicketForm() {
  const createMutation = useCreateSupportTicket();

  const [subject, setSubject] = useState("");
  const [category, setCategory] = useState<SupportTicketCategory | "">("");
  const [body, setBody] = useState("");
  const [attachmentKeys, setAttachmentKeys] = useState<string[]>([]);

  const [subjectError, setSubjectError] = useState<string | null>(null);
  const [categoryError, setCategoryError] = useState<string | null>(null);
  const [bodyError, setBodyError] = useState<string | null>(null);

  function validate(): boolean {
    let ok = true;

    const trimmedSubject = subject.trim();
    if (trimmedSubject.length === 0) {
      setSubjectError("Subject is required.");
      ok = false;
    } else if (trimmedSubject.length > SUBJECT_MAX) {
      setSubjectError(`Subject must be ${SUBJECT_MAX} characters or fewer.`);
      ok = false;
    } else {
      setSubjectError(null);
    }

    if (category === "") {
      setCategoryError("Pick a category so we can route your ticket.");
      ok = false;
    } else {
      setCategoryError(null);
    }

    const trimmedBody = body.trim();
    if (trimmedBody.length === 0) {
      setBodyError("Message is required.");
      ok = false;
    } else if (body.length > BODY_MAX) {
      setBodyError(`Message must be ${BODY_MAX} characters or fewer.`);
      ok = false;
    } else {
      setBodyError(null);
    }

    return ok;
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!validate()) return;
    // Validation guarantees category is non-empty by this point.
    const req: CreateSupportTicketRequest = {
      subject: subject.trim(),
      category: category as SupportTicketCategory,
      body: body.trim(),
      attachmentKeys: attachmentKeys.length === 0 ? undefined : attachmentKeys,
    };
    createMutation.mutate(req);
  }

  const submitting = createMutation.isPending;
  const serverError = createMutation.isError
    ? describeError(createMutation.error)
    : null;

  const bodyOverCap = body.length > BODY_MAX;

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <form
        onSubmit={handleSubmit}
        className="flex flex-col gap-6"
        aria-label="New support ticket"
        data-testid="new-support-ticket-form"
        noValidate
      >
        <div>
          <h1 className="text-xl font-bold tracking-tight font-display">
            New support ticket
          </h1>
          <p className="text-sm text-fg-muted mt-1">
            Tell us what&apos;s going on and our team will follow up here.
          </p>
        </div>

        <Section
          title="Subject"
          description="A short summary so we can spot it in the queue."
        >
          <Input
            label="Subject"
            value={subject}
            onChange={(e) => {
              setSubject(e.target.value);
              if (subjectError) setSubjectError(null);
            }}
            placeholder="e.g. Wallet deposit stuck"
            maxLength={SUBJECT_MAX}
            data-testid="ticket-subject-input"
            error={subjectError ?? undefined}
          />
        </Section>

        <Section
          title="Category"
          description="Pick the area that best matches your issue."
        >
          <div className="flex flex-col gap-1">
            <label
              htmlFor="ticket-category-select"
              className="text-xs font-medium text-fg-muted"
            >
              Category
            </label>
            <select
              id="ticket-category-select"
              value={category}
              onChange={(e) => {
                setCategory(e.target.value as SupportTicketCategory | "");
                if (categoryError) setCategoryError(null);
              }}
              data-testid="ticket-category-select"
              aria-invalid={categoryError ? true : undefined}
              className="h-12 w-full rounded-lg bg-bg-subtle px-4 text-fg ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand"
            >
              <option value="">Select a category...</option>
              {CATEGORY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            {categoryError && (
              <p
                className="text-xs text-danger"
                data-testid="ticket-category-error"
              >
                {categoryError}
              </p>
            )}
          </div>
        </Section>

        <Section
          title="Message"
          description="What happened, what you expected, and any helpful links."
        >
          <Textarea
            label="Message"
            value={body}
            onChange={(e) => {
              setBody(e.target.value);
              if (bodyError) setBodyError(null);
            }}
            placeholder="Describe the issue. Include relevant auction or transaction IDs if you have them."
            rows={8}
            data-testid="ticket-body-textarea"
            error={bodyError ?? undefined}
          />
          <div className="flex justify-end">
            <span
              className={
                "text-[11px] " +
                (bodyOverCap ? "text-danger" : "text-fg-muted")
              }
              data-testid="ticket-body-counter"
            >
              {body.length} / {BODY_MAX}
            </span>
          </div>
        </Section>

        <Section
          title="Attachments"
          description="Optional. Screenshots help us see what you're seeing."
        >
          <SupportAttachmentDropzone
            attachmentKeys={attachmentKeys}
            onAttachmentKeyAdded={(key) =>
              setAttachmentKeys((prev) =>
                prev.includes(key) ? prev : [...prev, key],
              )
            }
            onAttachmentKeyRemoved={(key) =>
              setAttachmentKeys((prev) => prev.filter((k) => k !== key))
            }
            disabled={submitting}
          />
        </Section>

        {serverError && (
          <div
            role="alert"
            data-testid="new-ticket-form-error"
            className="rounded-lg border border-danger/30 bg-danger-bg/50 px-4 py-3 text-sm text-danger"
          >
            {serverError}
          </div>
        )}

        <div className="flex justify-end gap-2">
          <Button
            type="submit"
            variant="primary"
            loading={submitting}
            disabled={submitting}
            data-testid="ticket-submit-btn"
          >
            Submit ticket
          </Button>
        </div>
      </form>
    </div>
  );
}
