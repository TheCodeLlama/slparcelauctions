import { Suspense } from "react";
import { AdminLedgerPage } from "@/components/admin/ledger/AdminLedgerPage";

export default function AdminLedgerRoute() {
  return (
    <Suspense fallback={<div className="text-sm text-fg-muted py-6">Loading…</div>}>
      <AdminLedgerPage />
    </Suspense>
  );
}
