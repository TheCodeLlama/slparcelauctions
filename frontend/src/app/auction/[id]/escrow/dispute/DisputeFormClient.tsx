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
import { escrowKey } from "@/app/auction/[id]/escrow/EscrowPageClient";
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
  auctionId: number;
  /**
   * Seller id sourced from the server-side auction fetch in the RSC shell.
   * Used with the authenticated user's id to derive the viewer's role —
   * `seller` when the ids match, `winner` otherwise. The dispute endpoint's
   * 200/403 gate guarantees only seller and winner reach this client with a
   * successful escrow fetch; non-party callers see an error surface bubble up
   * via the escrow {@code useQuery}.
   */
  sellerId: number;
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
export function DisputeFormClient({ auctionId, sellerId }: DisputeFormClientProps) {
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
      const returnTo = encodeURIComponent(`/auction/${auctionId}/escrow/dispute`);
      router.replace(`/login?next=${returnTo}`);
    }
  }, [session.status, auctionId, router]);

  const isAuthenticated = session.status === "authenticated";

  const {
    data: escrow,
    isLoading,
    error,
  } = useQuery({
    queryKey: escrowKey(auctionId),
    queryFn: () => getEscrowStatus(auctionId),
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

  // Derive the viewer's role from the server-seeded sellerId. Non-seller
  // authenticated callers must be the winner — the dispute endpoint's 403
  // gate rejects everyone else before the POST commits.
  const role: "seller" | "winner" =
    session.user.id === sellerId ? "seller" : "winner";

  if (isLoading) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <EscrowPageSkeleton />
      </EscrowPageLayout>
    );
  }

  if (error && isApiError(error) && error.status === 404) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <NoEscrowPanel auctionId={auctionId} />
      </EscrowPageLayout>
    );
  }

  if (error || !escrow) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <FormError
          message={
            error instanceof Error
              ? `Could not load escrow status: ${error.message}`
              : "Could not load escrow status."
          }
        />
        <BackToEscrowLink auctionId={auctionId} />
      </EscrowPageLayout>
    );
  }

  if (TERMINAL_STATES.has(escrow.state)) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <TerminalStatePanel escrow={escrow} auctionId={auctionId} />
      </EscrowPageLayout>
    );
  }

  return (
    <EscrowPageLayout auctionId={auctionId}>
      <DisputeFormBody
        escrow={escrow}
        auctionId={auctionId}
        role={role}
        onSuccess={() => {
          queryClient.invalidateQueries({ queryKey: escrowKey(auctionId) });
          toast.success("Dispute filed. SLPA staff will review.");
          router.push(`/auction/${auctionId}/escrow`);
        }}
        on409={() => {
          toast.error(
            "This escrow's state changed while you were filing. Please review.",
          );
          router.push(`/auction/${auctionId}/escrow`);
        }}
        onGenericError={(message) =>
          toast.error(`Failed to file dispute: ${message}`)
        }
      />
    </EscrowPageLayout>
  );
}

function BackToEscrowLink({ auctionId }: { auctionId: number }) {
  return (
    <Link
      href={`/auction/${auctionId}/escrow`}
      className="mt-4 inline-block text-primary hover:underline"
    >
      Back to escrow
    </Link>
  );
}

function NoEscrowPanel({ auctionId }: { auctionId: number }) {
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-6 text-center">
      <h2 className="text-title-md text-on-surface">
        No escrow exists for this auction
      </h2>
      <BackToEscrowLink auctionId={auctionId} />
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
  auctionId,
}: {
  escrow: EscrowStatusResponse;
  auctionId: number;
}) {
  const messages: Record<string, string> = {
    DISPUTED: `A dispute was filed on ${
      escrow.disputedAt
        ? new Date(escrow.disputedAt).toLocaleString()
        : "-"
    }. SLPA is reviewing.`,
    COMPLETED: `Escrow completed on ${
      escrow.completedAt
        ? new Date(escrow.completedAt).toLocaleString()
        : "-"
    }. If you have a concern, contact support.`,
    EXPIRED: "This escrow is in an EXPIRED state and is no longer active.",
    FROZEN: "This escrow is in a FROZEN state and is no longer active.",
  };
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-6">
      <h2 className="text-title-md text-on-surface">
        This escrow can no longer be disputed
      </h2>
      <p className="mt-2 text-body-md text-on-surface-variant">
        {messages[escrow.state]}
      </p>
      <BackToEscrowLink auctionId={auctionId} />
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
  auctionId,
  role,
  onSuccess,
  on409,
  onGenericError,
}: {
  escrow: EscrowStatusResponse;
  auctionId: number;
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

  // eslint-disable-next-line react-hooks/incompatible-library
  const description = watch("description");
  // eslint-disable-next-line react-hooks/incompatible-library
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
        `/api/v1/auctions/${auctionId}/escrow/dispute`,
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
      <div className="rounded-md bg-surface-container-low p-4 text-body-sm text-on-surface-variant">
        You&apos;re disputing escrow for <strong>{escrow.parcelName}</strong> —
        currently <strong>{escrow.state}</strong>, you are the{" "}
        <strong>{role}</strong>.
      </div>

      <div>
        <label
          htmlFor="reasonCategory"
          className="text-label-lg font-semibold text-on-surface"
        >
          Reason
        </label>
        <select
          id="reasonCategory"
          {...register("reasonCategory")}
          className="mt-2 w-full rounded-md border border-outline-variant bg-surface-container-lowest px-3 py-2 text-body-md"
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
            className="text-label-lg font-semibold text-on-surface"
          >
            SL transaction key{" "}
            <span className="text-label-sm font-normal text-on-surface-variant">
              (required for payment-not-credited)
            </span>
          </label>
          <input
            id="slTransactionKey"
            type="text"
            {...register("slTransactionKey")}
            placeholder="00000000-0000-0000-0000-000000000000"
            className="mt-2 w-full rounded-md border border-outline-variant bg-surface-container-lowest px-3 py-2 text-body-md font-mono"
          />
          {errors.slTransactionKey && (
            <FormError message={errors.slTransactionKey.message ?? ""} />
          )}
        </div>
      )}

      <div>
        <label
          htmlFor="description"
          className="text-label-lg font-semibold text-on-surface"
        >
          Description
        </label>
        <textarea
          id="description"
          rows={4}
          {...register("description")}
          className="mt-2 w-full rounded-md border border-outline-variant bg-surface-container-lowest px-3 py-2 text-body-md"
        />
        <div className="mt-1 flex items-center justify-between">
          <span className="text-label-sm text-on-surface-variant">
            {(description ?? "").length} / 2000
          </span>
          {errors.description && (
            <span className="text-label-sm text-error">
              {errors.description.message}
            </span>
          )}
        </div>
      </div>

      <DisputeEvidenceUploader files={files} onChange={setFiles} />

      <div className="flex items-center justify-between">
        <Link
          href={`/auction/${auctionId}/escrow`}
          className="text-label-lg text-on-surface-variant hover:text-on-surface"
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
