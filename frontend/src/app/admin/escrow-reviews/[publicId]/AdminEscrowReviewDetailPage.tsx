"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEscrowReview } from "@/lib/admin/escrowReviewHooks";
import { ExternalLink } from "@/components/ui/icons";
import type { AdminEscrowReviewDetail } from "@/lib/admin/escrowReviews";
import { ReviewResolutionPanel } from "./ReviewResolutionPanel";

const STEP_LABEL: Record<AdminEscrowReviewDetail["step"], string> = {
  SET_SELL_TO: "Set Sell To",
  BUY_PARCEL: "Buy Parcel",
};

const REASON_LABEL: Record<AdminEscrowReviewDetail["reason"], string> = {
  USER_REQUESTED: "User requested",
  BOT_PERSISTENT_FAILURE: "Bot persistent failure",
  WORLD_API_PERSISTENT_FAILURE: "World API persistent failure",
};

export function AdminEscrowReviewDetailPage({
  reviewPublicId,
}: {
  reviewPublicId: string;
}) {
  const router = useRouter();
  const { data, isLoading, error } = useEscrowReview(reviewPublicId);
  if (isLoading) return <p>Loading…</p>;
  if (error || !data)
    return <p className="text-danger">Failed to load escrow review</p>;

  return (
    <div className="space-y-4">
      <nav className="text-xs">
        <Link href="/admin/escrow-reviews" className="text-brand">
          ← Escrow Reviews
        </Link>
        <span className="opacity-40 mx-2">/</span>
        <span className="opacity-85">{data.auctionTitle}</span>
      </nav>

      <header className="bg-bg-muted rounded p-4 flex gap-4 items-center">
        <span
          className={`px-2.5 py-1 rounded text-[11px] ${
            data.status === "OPEN"
              ? "bg-danger-bg text-danger"
              : "bg-info-bg text-info"
          }`}
        >
          {data.status}
        </span>
        <div className="flex-1">
          <h1 className="text-base font-semibold">{data.auctionTitle}</h1>
          <p className="text-[11px] opacity-65">
            {data.parcelName} · {STEP_LABEL[data.step]} ·{" "}
            {REASON_LABEL[data.reason]} · Requested by {data.requestedRole} ·
            Opened {new Date(data.createdAt).toLocaleString()}
          </p>
        </div>
        {data.slurl && (
          <a
            href={data.slurl}
            target="_blank"
            rel="noreferrer"
            className="text-[11px] text-brand inline-flex items-center gap-1"
          >
            <ExternalLink className="size-3" />
            View parcel in Second Life
          </a>
        )}
      </header>

      <div className="grid grid-cols-[1fr_360px] gap-4">
        <div className="space-y-4">
          <section className="bg-bg-muted rounded p-4 space-y-2">
            <div className="text-[10px] uppercase opacity-60">
              Escrow snapshot
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs">
              <Field label="Escrow state" value={data.escrowState} />
              <Field
                label="Final bid"
                value={`L$ ${data.finalBidAmount.toLocaleString()}`}
              />
              <Field label="Funded" value={fmt(data.fundedAt)} />
              <Field
                label="Sell-To confirmed"
                value={fmt(data.sellToConfirmedAt)}
              />
              <Field
                label="Transfer confirmed"
                value={fmt(data.transferConfirmedAt)}
              />
              <Field
                label="Transfer deadline"
                value={fmt(data.transferDeadline)}
              />
            </dl>
          </section>

          <section className="bg-bg-muted rounded p-4 space-y-2">
            <div className="text-[10px] uppercase opacity-60">
              Observed evidence
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs">
              <Field
                label="Sell-To last result"
                value={data.sellToLastResult ?? "—"}
              />
              <Field
                label="Sell-To last checked"
                value={fmt(data.sellToLastCheckedAt)}
              />
              <Field
                label="Sell-To verify attempts"
                value={String(data.sellToVerifyAttempts)}
              />
              <Field
                label="Buy verify (seller)"
                value={String(data.buyVerifySellerAttempts)}
              />
              <Field
                label="Buy verify (buyer)"
                value={String(data.buyVerifyBuyerAttempts)}
              />
              <Field
                label="Consecutive bot failures"
                value={String(data.consecutiveSellToBotFailures)}
              />
              <Field
                label="Consecutive World-API failures"
                value={String(data.consecutiveWorldApiFailures)}
              />
            </dl>
          </section>

          {data.status !== "OPEN" && (
            <section className="bg-bg-muted rounded p-4 space-y-2">
              <div className="text-[10px] uppercase opacity-60">Resolution</div>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs">
                <Field label="Resolution" value={data.resolution ?? "—"} />
                <Field label="Resolved at" value={fmt(data.resolvedAt)} />
              </dl>
              {data.adminNotes && (
                <p className="text-xs opacity-80 whitespace-pre-wrap">
                  {data.adminNotes}
                </p>
              )}
            </section>
          )}
        </div>

        {data.status === "OPEN" ? (
          <ReviewResolutionPanel
            review={data}
            onResolved={() => router.push("/admin/escrow-reviews")}
          />
        ) : (
          <aside className="bg-bg-muted rounded p-4 text-xs text-fg-muted">
            This review has been {data.status.toLowerCase()}.
          </aside>
        )}
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="opacity-55">{label}</dt>
      <dd className="font-medium text-right">{value}</dd>
    </>
  );
}

function fmt(value: string | null) {
  return value ? new Date(value).toLocaleString() : "—";
}
