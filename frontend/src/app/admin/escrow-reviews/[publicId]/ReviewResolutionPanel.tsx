"use client";

import { useState } from "react";
import { useResolveEscrowReview } from "@/lib/admin/escrowReviewHooks";
import type {
  AdminEscrowReviewDetail,
  ManualReviewResolution,
} from "@/lib/admin/escrowReviews";

type Props = { review: AdminEscrowReviewDetail; onResolved: () => void };

const ACTIONS: {
  value: ManualReviewResolution;
  label: string;
  subtitle: string;
}[] = [
  {
    value: "FORCE_CONFIRM_SELL_TO",
    label: "Force confirm Sell To",
    subtitle:
      "Same path as the bot SELL_TO_OK outcome — advances escrow to the Buy-Parcel step.",
  },
  {
    value: "FORCE_COMPLETE_TRANSFER",
    label: "Force complete transfer",
    subtitle:
      "Confirms the in-world transfer (admin verified) → seller payout.",
  },
  {
    value: "REFUND_WINNER",
    label: "Refund winner",
    subtitle: "Escrow → EXPIRED, winner refunded via the expire/refund path.",
  },
  {
    value: "DISMISS",
    label: "Dismiss",
    subtitle: "Close the review with no escrow state change.",
  },
];

export function ReviewResolutionPanel({ review, onResolved }: Props) {
  const [action, setAction] = useState<ManualReviewResolution>(
    "FORCE_CONFIRM_SELL_TO",
  );
  const [note, setNote] = useState("");
  const mutation = useResolveEscrowReview(review.reviewPublicId);

  const submit = () => {
    if (note.trim().length === 0) return;
    mutation.mutate(
      { action, adminNote: note },
      { onSuccess: onResolved },
    );
  };

  return (
    <aside className="bg-bg-muted rounded p-4 space-y-3">
      <div className="text-[10px] uppercase opacity-60">Resolution</div>

      <fieldset className="space-y-2">
        {ACTIONS.map((a) => (
          <RadioRow
            key={a.value}
            value={a.value}
            current={action}
            onChange={setAction}
            label={a.label}
            subtitle={a.subtitle}
          />
        ))}
      </fieldset>

      <div>
        <label className="text-[10px] uppercase opacity-55 block mb-1">
          Admin note <span className="text-danger normal-case">(required)</span>
        </label>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="What did you verify and why this action?"
          maxLength={500}
          className="w-full h-20 text-xs bg-bg-subtle rounded p-2 resize-y"
        />
        <div className="text-[10px] opacity-40 mt-1">{note.length} / 500</div>
      </div>

      <button
        type="button"
        disabled={mutation.isPending || note.trim().length === 0}
        onClick={submit}
        className="w-full py-2 bg-brand text-white rounded text-xs font-semibold disabled:opacity-50"
      >
        {mutation.isPending ? "Applying…" : "Apply resolution"}
      </button>

      {mutation.isError && (
        <p className="text-[10px] text-danger">
          Failed to apply: {(mutation.error as Error).message}
        </p>
      )}
    </aside>
  );
}

function RadioRow({
  value,
  current,
  onChange,
  label,
  subtitle,
}: {
  value: ManualReviewResolution;
  current: ManualReviewResolution;
  onChange: (v: ManualReviewResolution) => void;
  label: string;
  subtitle: string;
}) {
  return (
    <label className="flex gap-2 items-start text-xs cursor-pointer">
      <input
        type="radio"
        checked={current === value}
        onChange={() => onChange(value)}
        className="mt-0.5"
      />
      <span>
        <span className="font-medium">{label}</span>
        <p className="text-[10px] opacity-65 mt-0.5">{subtitle}</p>
      </span>
    </label>
  );
}
