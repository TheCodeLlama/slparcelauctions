"use client";
import { useState, useEffect } from "react";
import Link from "next/link";
import { useAdminFraudFlag } from "@/hooks/admin/useAdminFraudFlag";
import { useDismissFraudFlag } from "@/hooks/admin/useDismissFraudFlag";
import { useReinstateFraudFlag } from "@/hooks/admin/useReinstateFraudFlag";
import { useOwnershipRecheck } from "@/lib/admin/ownershipHooks";
import { useToast } from "@/components/ui/Toast/useToast";
import { Button } from "@/components/ui/Button";
import { ReasonBadge } from "./ReasonBadge";
import { ReinstateBanner } from "./ReinstateBanner";
import { SiblingFlagWarning } from "./SiblingFlagWarning";
import { FraudFlagEvidence } from "./FraudFlagEvidence";
import { NotesField } from "./NotesField";

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

type Props = {
  flagId: number | null;
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
};

export function FraudFlagSlideOver({ flagId, hasPrev, hasNext, onPrev, onNext, onClose }: Props) {
  const [notes, setNotes] = useState("");
  const { data: detail, isLoading } = useAdminFraudFlag(flagId);
  const dismiss = useDismissFraudFlag();
  const reinstate = useReinstateFraudFlag();
  const recheck = useOwnershipRecheck();
  const toast = useToast();

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNotes("");
  }, [flagId]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!flagId) return null;

  const handleRecheck = () => {
    if (!detail?.auction) return;
    recheck.mutate(detail.auction.id, {
      onSuccess: (result) => {
        if (result.ownerMatch) {
          toast.success("Owner match — no change.");
        } else if (result.auctionStatus === "SUSPENDED") {
          toast.error("Owner mismatch detected. Auction suspended.");
        } else {
          toast.warning("Owner mismatch detected.");
        }
      },
      onError: (e) => toast.error(`Re-check failed: ${(e as Error).message}`),
    });
  };

  const isPending = dismiss.isPending || reinstate.isPending;
  const notesEmpty = notes.trim().length === 0;
  const canAct = !notesEmpty && !isPending;
  const canReinstate = canAct && detail?.auction?.status === "SUSPENDED";

  const handleDismiss = () => {
    if (!detail || !canAct) return;
    dismiss.mutate(
      { flagId: detail.id, adminNotes: notes },
      { onSuccess: () => { onClose(); } }
    );
  };

  const handleReinstate = () => {
    if (!detail || !canReinstate) return;
    reinstate.mutate(
      { flagId: detail.id, adminNotes: notes },
      { onSuccess: () => { onClose(); } }
    );
  };

  return (
    <>
      <div
        className="fixed inset-0 z-30 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <aside
        className="fixed top-16 right-0 bottom-0 z-40 w-[520px] flex flex-col bg-bg-subtle border-l border-border-subtle shadow-md overflow-hidden"
        data-testid="fraud-flag-slideover"
        role="dialog"
        aria-modal="true"
        aria-label="Fraud flag detail"
      >
        <div className="flex items-center justify-between px-5 py-3 border-b border-border-subtle shrink-0">
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onPrev}
              disabled={!hasPrev}
              aria-label="Previous flag"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted disabled:opacity-30 disabled:pointer-events-none"
            >
              ←
            </button>
            <button
              type="button"
              onClick={onNext}
              disabled={!hasNext}
              aria-label="Next flag"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted disabled:opacity-30 disabled:pointer-events-none"
            >
              →
            </button>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 flex flex-col gap-4">
          {isLoading && (
            <div className="text-sm text-fg-muted">Loading…</div>
          )}

          {detail && (
            <>
              <div className="flex items-center gap-2 flex-wrap">
                <ReasonBadge reason={detail.reason} />
                <span className="text-[11px] font-medium text-fg-muted">
                  Flag #{detail.id}
                </span>
              </div>

              {detail.auction && (
                <div>
                  <div className="text-sm font-semibold text-fg">
                    {detail.auction.title}
                  </div>
                  <div className="flex flex-wrap gap-x-3 gap-y-0.5 mt-1 text-sm text-fg-muted">
                    <span>Auction #{detail.auction.id}</span>
                    {detail.auction.sellerDisplayName && (
                      <span>
                        Seller:{" "}
                        <Link
                          href={`/users/${detail.auction.sellerUserId}`}
                          className="text-brand underline underline-offset-2"
                        >
                          {detail.auction.sellerDisplayName}
                        </Link>
                      </span>
                    )}
                    <span>Detected {formatDateTime(detail.detectedAt)}</span>
                  </div>
                </div>
              )}

              <ReinstateBanner detail={detail} />
              <SiblingFlagWarning count={detail.siblingOpenFlagCount} />
              <FraudFlagEvidence detail={detail} />

              {detail.resolvedAt ? (
                <div className="rounded-lg bg-bg-muted px-4 py-3 flex flex-col gap-1">
                  <div className="text-xs font-medium text-fg">Resolved</div>
                  <div className="text-sm text-fg-muted">
                    By{" "}
                    <span className="text-fg">
                      {detail.resolvedByDisplayName ?? "(unknown)"}
                    </span>{" "}
                    on {formatDateTime(detail.resolvedAt)}
                  </div>
                  {detail.adminNotes && (
                    <div className="mt-1 text-sm text-fg whitespace-pre-wrap">
                      {detail.adminNotes}
                    </div>
                  )}
                </div>
              ) : (
                <div className="flex flex-col gap-3">
                  <NotesField value={notes} onChange={setNotes} disabled={isPending} />
                  <div className="flex gap-2 flex-wrap">
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={handleDismiss}
                      disabled={!canAct}
                      loading={dismiss.isPending}
                      data-testid="dismiss-btn"
                    >
                      Dismiss flag
                    </Button>
                    <Button
                      variant="destructive"
                      size="sm"
                      onClick={handleReinstate}
                      disabled={!canReinstate}
                      loading={reinstate.isPending}
                      title={
                        detail.auction?.status !== "SUSPENDED"
                          ? "Auction must be SUSPENDED to reinstate"
                          : undefined
                      }
                      data-testid="reinstate-btn"
                    >
                      Reinstate auction
                    </Button>
                  </div>
                  {detail.auction && (
                    <div className="border-t border-border-subtle pt-3">
                      <Button
                        variant="tertiary"
                        size="sm"
                        onClick={handleRecheck}
                        disabled={recheck.isPending}
                        loading={recheck.isPending}
                        data-testid="recheck-ownership-btn"
                      >
                        {recheck.isPending ? "Checking…" : "↻ Re-check ownership now"}
                      </Button>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      </aside>
    </>
  );
}
