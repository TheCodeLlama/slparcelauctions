import type { PendingVerification } from "@/types/auction";

export interface VerificationMethodSaleToBotProps {
  pending: PendingVerification;
}

/**
 * SALE_TO_BOT in-progress panel. Instructions for setting the parcel
 * for-sale to the SLPAEscrow Resident account so the bot can verify
 * ownership via the forced sale event.
 *
 * {@code pending.instructions} is a backend-provided status line for
 * the bot task (e.g., "Waiting for bot to pick up your task —
 * this can take several minutes during peak hours."). Task 2 M3
 * flagged that sub-spec 1 can emit technical-ish strings like
 * "Bot: PARCEL_LOCKED" here; we strip a leading {@code "Bot: "}
 * prefix so the seller sees the state name without the internal
 * namespace, and render what's left verbatim.
 *
 * No countdown is shown — the bot-task deadline is 48h (spec §5.4.3),
 * long enough that a ticking countdown would be stressful rather than
 * useful.
 */
function cleanBotStatus(raw: string): string {
  return raw.startsWith("Bot: ") ? raw.slice(5) : raw;
}

export function VerificationMethodSaleToBot({
  pending,
}: VerificationMethodSaleToBotProps) {
  const status = pending.instructions ? cleanBotStatus(pending.instructions) : null;

  return (
    <div className="flex flex-col gap-4">
      <section className="rounded-default bg-surface-container-low p-6 flex flex-col gap-3">
        <h3 className="text-title-md text-on-surface">
          Set your land for sale to SLPAEscrow Resident
        </h3>
        <ol className="list-decimal list-inside flex flex-col gap-1 text-body-md text-on-surface">
          <li>Open the SL Land menu.</li>
          <li>Find the parcel you&apos;re listing.</li>
          <li>
            Choose <em>Set Land for Sale…</em>
          </li>
          <li>
            Buyer: <strong>SLPAEscrow Resident</strong>
          </li>
          <li>
            Price: <strong>L$999,999,999</strong>
          </li>
          <li>
            Click <em>Sell</em> to confirm.
          </li>
        </ol>
        <p className="text-body-sm text-on-surface-variant">
          The bot will detect the sale within a few minutes. You do not need
          to keep this page open.
        </p>
      </section>
      {status && (
        <div
          role="status"
          className="rounded-default bg-surface-container-high px-4 py-3 text-body-sm text-on-surface"
        >
          {status}
        </div>
      )}
    </div>
  );
}
