"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  EscrowDisputeReasonCategory,
  EscrowState,
  EscrowStatusResponse,
} from "@/types/escrow";
import { useAuth } from "@/lib/auth";
import { getEscrowStatus } from "@/lib/api/escrow";
import { api, isApiError } from "@/lib/api";
import { escrowKey } from "@/app/auction/[publicId]/escrow/EscrowPageClient";
import { EscrowPageLayout } from "@/components/escrow/EscrowPageLayout";
import { EscrowPageSkeleton } from "@/components/escrow/EscrowPageSkeleton";
import { DisputeEvidenceUploader } from "@/components/escrow/DisputeEvidenceUploader";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useToast } from "@/components/ui/Toast";

/**
 * Zod schema for the dispute-file form. `reasonCategory` is a closed set
 * that mirrors the backend enum (see sub-spec 1 §3 +
 * {@link EscrowDisputeReasonCategory}); {@code description} bounds match
 * the backend's {@code @Size(min = 10, max = 2000)} on the request DTO
 * so client-side validation rejects the same strings server-side
 * validation would.
 */
const disputeSchema = z
  .object({
    reasonCategory: z.enum([
      "SELLER_NOT_RESPONSIVE",
      "WRONG_PARCEL_TRANSFERRED",
      "PAYMENT_NOT_CREDITED",
      "FRAUD_SUSPECTED",
      "OTHER",
    ]),
    description: z
      .string()
      .min(10, "Please describe the issue (at least 10 characters)")
      .max(2000, "Description is too long (max 2000 characters)"),
    slTransactionKey: z.string().optional(),
  })
  .superRefine((data, ctx) => {
    if (
      data.reasonCategory === "PAYMENT_NOT_CREDITED" &&
      !data.slTransactionKey?.trim()
    ) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["slTransactionKey"],
        message: "SL transaction key is required for payment-not-credited disputes",
      });
    }
  });

type DisputeFormValues = z.infer<typeof disputeSchema>;

const REASON_LABELS: Record<EscrowDisputeReasonCategory, string> = {
  SELLER_NOT_RESPONSIVE: "Seller isn't responding",
  WRONG_PARCEL_TRANSFERRED: "Wrong parcel transferred to me",
  PAYMENT_NOT_CREDITED: "I paid but the escrow didn't move to funded",
  FRAUD_SUSPECTED: "I suspect fraud",
  OTHER: "Other / something else",
};

/**
 * States that can no longer transition to DISPUTED. The backend's state
 * machine (sub-spec 1 §3) rejects a dispute on any of these with 409
 * ESCROW_INVALID_TRANSITION — the UI front-runs that rejection by
 * replacing the form with a read-only explainer panel.
 */
const TERMINAL_STATES: ReadonlySet<EscrowState> = new Set<EscrowState>([
  "COMPLETED",
  "EXPIRED",
  "DISPUTED",
  "FROZEN",
]);

export interface DisputeFormClientProps {
  auctionPublicId: string;
  /**
   * Seller public id sourced from the server-side auction fetch in the RSC
   * shell. Used with the authenticated user's publicId to derive the viewer's
   * role — `seller` when the ids match, `winner` otherwise. The dispute
   * endpoint's 200/403 gate guarantees only seller and winner reach this
   * client with a successful escrow fetch; non-party callers see an error
   * surface bubble up via the escrow {@code useQuery}.
   */
  sellerPublicId: string;
}

/**
 * Client shell for `/auction/{id}/escrow/dispute`.
 *
 * Mirrors {@code EscrowPageClient}: runs the authenticated-viewer gate
 * (unauthenticated → /login redirect with returnTo), derives the caller's
 * role from the server-seeded {@code sellerId}, seeds the escrow
 * {@code useQuery} so the form knows the source state (needed to 409-
 * front-run the TERMINAL_STATES panel), and wires the dispute
 * {@code useMutation} to the two navigation paths: success → back to the
 * escrow page with a confirmation toast; 409 {@code ESCROW_INVALID_TRANSITION}
 * → back to the escrow page with an explanatory error toast (the user can
 * see the authoritative state there).
 *
 * Retries are disabled on the escrow query so 404 / 403 surface
 * immediately rather than spinning through React Query's exponential
 * backoff — the dispute form has no graceful fallback for a missing or
 * forbidden escrow beyond rendering the empty / error panel.
 *
 * See sub-spec 2 §6 (dispute flow), §7 (authz), §15 (state matrix).
 */
export function DisputeFormClient({ auctionPublicId, sellerPublicId }: DisputeFormClientProps) {
  const session = useAuth();
  const router = useRouter();
  const queryClient = useQueryClient();
  const toast = useToast();

  // Unauthenticated users get redirected to /login with returnTo so the
  // post-login hop lands them back on the dispute form. Matches the
  // EscrowPageClient gate — client-side (not RSC) because the auth stack
  // lives entirely in the browser.
  useEffect(() => {
    if (session.status === "unauthenticated") {
      const returnTo = encodeURIComponent(`/auction/${auctionPublicId}/escrow/dispute`);
      router.replace(`/login?next=${returnTo}`);
    }
  }, [session.status, auctionPublicId, router]);

  const isAuthenticated = session.status === "authenticated";

  const {
    data: escrow,
    isLoading,
    error,
  } = useQuery({
    queryKey: escrowKey(auctionPublicId),
    queryFn: () => getEscrowStatus(auctionPublicId),
    // Gate the fetch on the authed state so anonymous callers don't fire a
    // 401/403 on the way to being redirected.
    enabled: isAuthenticated,
    refetchOnWindowFocus: true,
    // Let 404 / 403 surface immediately; no silent retries on auth failures.
    retry: false,
  });

  if (session.status === "loading") {
    return <LoadingSpinner label="Loading..." />;
  }
  if (session.status === "unauthenticated") {
    // Redirect is in-flight via the effect above. Render null so the
    // protected surface doesn't flash before the navigation resolves.
    return null;
  }

  // Derive the viewer's role from the server-seeded sellerPublicId. Non-seller
  // authenticated callers must be the winner — the dispute endpoint's 403
  // gate rejects everyone else before the POST commits.
  const role: "seller" | "winner" =
    session.user.publicId === sellerPublicId ? "seller" : "winner";

  if (isLoading) {
    return (
      <EscrowPageLayout auctionPublicId={auctionPublicId}>
        <EscrowPageSkeleton />
      </EscrowPageLayout>
    );
  }

  if (error && isApiError(error) && error.status === 404) {
    return (
      <EscrowPageLayout auctionPublicId={auctionPublicId}>
        <NoEscrowPanel auctionPublicId={auctionPublicId} />
      </EscrowPageLayout>
    );
  }

  if (error || !escrow) {
    return (
      <EscrowPageLayout auctionPublicId={auctionPublicId}>
        <FormError
          message={
            error instanceof Error
              ? `Could not load escrow status: ${error.message}`
              : "Could not load escrow status."
          }
        />
        <BackToEscrowLink auctionPublicId={auctionPublicId} />
      </EscrowPageLayout>
    );
  }

  if (TERMINAL_STATES.has(escrow.state)) {
    return (
      <EscrowPageLayout auctionPublicId={auctionPublicId}>
        <TerminalStatePanel escrow={escrow} auctionPublicId={auctionPublicId} />
      </EscrowPageLayout>
    );
  }

  return (
    <EscrowPageLayout auctionPublicId={auctionPublicId}>
      <DisputeFormBody
        escrow={escrow}
        auctionPublicId={auctionPublicId}
        role={role}
        onSuccess={() => {
          queryClient.invalidateQueries({ queryKey: escrowKey(auctionPublicId) });
          toast.success("Dispute filed. SLParcels staff will review.");
          router.push(`/auction/${auctionPublicId}/escrow`);
        }}
        on409={() => {
          toast.error(
            "This escrow's state changed while you were filing. Please review.",
          );
          router.push(`/auction/${auctionPublicId}/escrow`);
        }}
        onGenericError={(message) =>
          toast.error(`Failed to file dispute: ${message}`)
        }
      />
    </EscrowPageLayout>
  );
}

function BackToEscrowLink({ auctionPublicId }: { auctionPublicId: string }) {
  return (
    <Link
      href={`/auction/${auctionPublicId}/escrow`}
      className="mt-4 inline-block text-brand hover:underline"
    >
      Back to escrow
    </Link>
  );
}

function NoEscrowPanel({ auctionPublicId }: { auctionPublicId: string }) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-raised p-6 text-center">
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        No escrow exists for this auction
      </h2>
      <BackToEscrowLink auctionPublicId={auctionPublicId} />
    </div>
  );
}

/**
 * Read-only panel shown when the escrow is already in a terminal state.
 * Front-runs the backend's 409 {@code ESCROW_INVALID_TRANSITION} by
 * rejecting the submit client-side — a user clicking into the dispute
 * form after the escrow completed / expired / was already disputed sees
 * an explainer and a link back to the authoritative status page, not a
 * form they'd fill out only to have rejected.
 */
function TerminalStatePanel({
  escrow,
  auctionPublicId,
}: {
  escrow: EscrowStatusResponse;
  auctionPublicId: string;
}) {
  const messages: Record<string, string> = {
    DISPUTED: `A dispute was filed on ${
      escrow.disputedAt
        ? new Date(escrow.disputedAt).toLocaleString()
        : "-"
    }. SLParcels is reviewing.`,
    COMPLETED: `Escrow completed on ${
      escrow.completedAt
        ? new Date(escrow.completedAt).toLocaleString()
        : "-"
    }. If you have a concern, contact support.`,
    EXPIRED: "This escrow is in an EXPIRED state and is no longer active.",
    FROZEN: "This escrow is in a FROZEN state and is no longer active.",
  };
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-raised p-6">
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        This escrow can no longer be disputed
      </h2>
      <p className="mt-2 text-sm text-fg-muted">
        {messages[escrow.state]}
      </p>
      <BackToEscrowLink auctionPublicId={auctionPublicId} />
    </div>
  );
}

/**
 * Form body. Extracted from the parent so the form's hooks
 * ({@code useForm} + {@code useMutation}) only mount on the branch that
 * actually renders a form — the terminal-state and error branches above
 * never call them. This keeps the hook-order invariant without a guard
 * rendering a form that the user can't submit.
 */
function DisputeFormBody({
  escrow,
  auctionPublicId,
  role,
  onSuccess,
  on409,
  onGenericError,
}: {
  escrow: EscrowStatusResponse;
  auctionPublicId: string;
  role: "seller" | "winner";
  onSuccess: () => void;
  on409: () => void;
  onGenericError: (message: string) => void;
}) {
  const [files, setFiles] = useState<File[]>([]);

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<DisputeFormValues>({
    resolver: zodResolver(disputeSchema),
    defaultValues: { reasonCategory: "OTHER", description: "", slTransactionKey: "" },
  });

  const description = watch("description");
  const reasonCategory = watch("reasonCategory");

  const mutation = useMutation({
    mutationFn: (values: DisputeFormValues) => {
      const fd = new FormData();
      fd.append(
        "body",
        new Blob(
          [
            JSON.stringify({
              reasonCategory: values.reasonCategory,
              description: values.description,
              slTransactionKey: values.slTransactionKey?.trim() || null,
            }),
          ],
          { type: "application/json" },
        ),
      );
      files.forEach((f) => fd.append("files", f));
      return api.post<EscrowStatusResponse>(
        `/api/v1/auctions/${auctionPublicId}/escrow/dispute`,
        fd,
      );
    },
    onSuccess,
    onError: (err) => {
      // ApiError nests the domain code on `problem.code` (see
      // ProblemDetail extension in lib/api.ts). The backend emits
      // ESCROW_INVALID_TRANSITION on 409 — treat it as a "state changed
      // under me" race and bounce the user back to the authoritative
      // status page. 403 is a dedicated copy so the user understands it's
      // not a transient error.
      if (
        isApiError(err) &&
        err.status === 409 &&
        err.problem?.code === "ESCROW_INVALID_TRANSITION"
      ) {
        on409();
      } else if (isApiError(err) && err.status === 403) {
        onGenericError("You don't have permission to dispute this escrow.");
      } else {
        onGenericError(err instanceof Error ? err.message : "Unknown error");
      }
    },
  });

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(values);
  });

  return (
    <form onSubmit={onSubmit} className="space-y-5" noValidate>
      <div className="rounded-md bg-bg-subtle p-4 text-xs text-fg-muted">
        Escrow state: <strong>{escrow.state}</strong>. You are the{" "}
        <strong>{role}</strong>.
      </div>

      <div>
        <label
          htmlFor="reasonCategory"
          className="text-sm font-medium font-semibold text-fg"
        >
          Reason
        </label>
        <select
          id="reasonCategory"
          {...register("reasonCategory")}
          className="mt-2 w-full rounded-md border border-border-subtle bg-surface-raised px-3 py-2 text-sm"
        >
          {Object.entries(REASON_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {errors.reasonCategory && (
          <FormError message={errors.reasonCategory.message ?? ""} />
        )}
      </div>

      {reasonCategory === "PAYMENT_NOT_CREDITED" && (
        <div>
          <label
            htmlFor="slTransactionKey"
            className="text-sm font-medium font-semibold text-fg"
          >
            SL transaction key{" "}
            <span className="text-[11px] font-medium font-normal text-fg-muted">
              (required for payment-not-credited)
            </span>
          </label>
          <input
            id="slTransactionKey"
            type="text"
            {...register("slTransactionKey")}
            placeholder="00000000-0000-0000-0000-000000000000"
            className="mt-2 w-full rounded-md border border-border-subtle bg-surface-raised px-3 py-2 text-sm font-mono"
          />
          {errors.slTransactionKey && (
            <FormError message={errors.slTransactionKey.message ?? ""} />
          )}
        </div>
      )}

      <div>
        <label
          htmlFor="description"
          className="text-sm font-medium font-semibold text-fg"
        >
          Description
        </label>
        <textarea
          id="description"
          rows={4}
          {...register("description")}
          className="mt-2 w-full rounded-md border border-border-subtle bg-surface-raised px-3 py-2 text-sm"
        />
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[11px] font-medium text-fg-muted">
            {(description ?? "").length} / 2000
          </span>
          {errors.description && (
            <span className="text-[11px] font-medium text-danger">
              {errors.description.message}
            </span>
          )}
        </div>
      </div>

      <DisputeEvidenceUploader files={files} onChange={setFiles} />

      <div className="flex items-center justify-between">
        <Link
          href={`/auction/${auctionPublicId}/escrow`}
          className="text-sm font-medium text-fg-muted hover:text-fg"
        >
          Cancel
        </Link>
        <Button
          type="submit"
          variant="primary"
          size="md"
          loading={isSubmitting || mutation.isPending}
        >
          File dispute
        </Button>
      </div>
    </form>
  );
}
