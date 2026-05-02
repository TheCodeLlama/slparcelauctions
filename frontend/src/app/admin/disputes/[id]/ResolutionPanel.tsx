"use client";

import { useState } from "react";
import { useResolveDispute } from "@/lib/admin/disputeHooks";
import type {
  AdminDisputeAction, AdminDisputeDetail,
} from "@/lib/admin/disputes";

type Props = { dispute: AdminDisputeDetail; onResolved: () => void };

export function ResolutionPanel({ dispute, onResolved }: Props) {
  const isFrozen = dispute.status === "FROZEN";
  const defaultAction: AdminDisputeAction = isFrozen ? "RESUME_TRANSFER" : "RESET_TO_FUNDED";
  const [action, setAction] = useState<AdminDisputeAction>(defaultAction);
  const [alsoCancel, setAlsoCancel] = useState(false);
  const [note, setNote] = useState("");
  const mutation = useResolveDispute(dispute.escrowId);

  const showCancelCheckbox = action === "RESET_TO_FUNDED";

  const submit = () => {
    if (note.trim().length === 0) return;
    mutation.mutate(
      { action, alsoCancelListing: showCancelCheckbox && alsoCancel, adminNote: note },
      { onSuccess: onResolved }
    );
  };

  return (
    <aside className="bg-bg-muted rounded p-4 space-y-3">
      <div className="text-[10px] uppercase opacity-60">Resolution</div>

      <fieldset className="space-y-2">
        {isFrozen ? (
          <>
            <RadioRow value="RESUME_TRANSFER" current={action} onChange={setAction}
                     label="Resume transfer"
                     subtitle="FROZEN → TRANSFER_PENDING. Bot picks up transfer next sweep." />
            <RadioRow value="MARK_EXPIRED" current={action} onChange={setAction}
                     label="Mark expired"
                     subtitle="FROZEN → EXPIRED. Refund queued." />
          </>
        ) : (
          <>
            <RadioRow value="RECOGNIZE_PAYMENT" current={action} onChange={setAction}
                     label="Recognize payment & dispatch"
                     subtitle="DISPUTED → TRANSFER_PENDING. Bot picks up transfer next sweep." />
            <RadioRow value="RESET_TO_FUNDED" current={action} onChange={setAction}
                     label="Reset to FUNDED"
                     subtitle="DISPUTED → FUNDED. Winner can re-try terminal pay." />
          </>
        )}
      </fieldset>

      {showCancelCheckbox && (
        <label className="flex gap-2 items-start text-xs cursor-pointer bg-bg-subtle p-2.5 rounded">
          <input type="checkbox" checked={alsoCancel} onChange={(e) => setAlsoCancel(e.target.checked)} />
          <span>
            <span className="font-medium">Also cancel this listing and refund winner</span>
            <span className="opacity-65"> (no seller penalty)</span>
            <p className="text-[10px] opacity-60 mt-1">Bidders refunded; cancellation logged as admin-initiated.</p>
          </span>
        </label>
      )}

      <div>
        <label className="text-[10px] uppercase opacity-55 block mb-1">
          Admin note <span className="text-danger-flat normal-case">(required)</span>
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
        <p className="text-[10px] text-danger-flat">Failed to apply: {(mutation.error as Error).message}</p>
      )}
    </aside>
  );
}

function RadioRow({ value, current, onChange, label, subtitle }: {
  value: AdminDisputeAction;
  current: AdminDisputeAction;
  onChange: (v: AdminDisputeAction) => void;
  label: string;
  subtitle: string;
}) {
  return (
    <label className="flex gap-2 items-start text-xs cursor-pointer">
      <input type="radio" checked={current === value} onChange={() => onChange(value)} className="mt-0.5" />
      <span>
        <span className="font-medium">{label}</span>
        <p className="text-[10px] opacity-65 mt-0.5">{subtitle}</p>
      </span>
    </label>
  );
}
