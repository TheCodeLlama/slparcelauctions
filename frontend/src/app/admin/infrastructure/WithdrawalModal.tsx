"use client";
import { useState } from "react";
import { useRequestWithdrawal } from "@/lib/admin/infrastructureHooks";

type Props = { onClose: () => void; available: number };

export function WithdrawalModal({ onClose, available }: Props) {
  const [amount, setAmount] = useState<number>(0);
  const [recipientUuid, setRecipientUuid] = useState("");
  const [notes, setNotes] = useState("");
  const mutation = useRequestWithdrawal();

  const valid = amount > 0 && amount <= available && /^[0-9a-f-]{36}$/i.test(recipientUuid);

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center" onClick={onClose}>
      <div className="bg-surface rounded p-5 max-w-md w-full" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-sm font-semibold mb-3">Withdraw to Account</h2>
        <div className="space-y-3 text-xs">
          <div>
            <label className="opacity-65 block mb-1">Amount (L$)</label>
            <input
              type="number" value={amount}
              onChange={(e) => setAmount(Number(e.target.value))}
              max={available}
              className="w-full bg-surface-container-low p-2 rounded"
            />
            <div className="text-[10px] opacity-60 mt-1">
              Available: L$ {available.toLocaleString()}
            </div>
          </div>
          <div>
            <label className="opacity-65 block mb-1">Recipient SL UUID</label>
            <input
              value={recipientUuid}
              onChange={(e) => setRecipientUuid(e.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
              className="w-full bg-surface-container-low p-2 rounded font-mono"
            />
          </div>
          <div>
            <label className="opacity-65 block mb-1">Notes (optional, max 1000)</label>
            <textarea
              value={notes} onChange={(e) => setNotes(e.target.value)}
              maxLength={1000}
              className="w-full bg-surface-container-low p-2 rounded h-20 resize-y"
            />
          </div>
          <div className="flex gap-2">
            <button type="button" onClick={onClose}
                    className="flex-1 px-3 py-2 border border-outline rounded">Cancel</button>
            <button
              type="button"
              disabled={!valid || mutation.isPending}
              onClick={() => mutation.mutate({ amount, recipientUuid, notes }, { onSuccess: onClose })}
              className="flex-1 px-3 py-2 bg-primary text-on-primary rounded font-semibold disabled:opacity-50"
            >{mutation.isPending ? "Submitting…" : "Withdraw"}</button>
          </div>
          {mutation.isError && (
            <p className="text-[10px] text-error">{(mutation.error as Error).message}</p>
          )}
        </div>
      </div>
    </div>
  );
}
