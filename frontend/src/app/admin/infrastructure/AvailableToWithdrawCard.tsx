"use client";
import { useState } from "react";
import { useAvailableToWithdraw } from "@/lib/admin/infrastructureHooks";
import { WithdrawalModal } from "./WithdrawalModal";

export function AvailableToWithdrawCard() {
  const { data } = useAvailableToWithdraw();
  const [open, setOpen] = useState(false);
  return (
    <section className="bg-bg-muted rounded p-4">
      <h2 className="text-sm font-semibold mb-2">Available to withdraw</h2>
      <p className="text-2xl font-bold mb-3">L$ {data?.available?.toLocaleString() ?? "—"}</p>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="px-3 py-2 bg-brand text-white rounded text-xs font-semibold"
      >Withdraw to Account</button>
      {open && <WithdrawalModal onClose={() => setOpen(false)} available={data?.available ?? 0} />}
    </section>
  );
}
