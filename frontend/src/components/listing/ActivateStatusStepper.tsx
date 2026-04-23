import { Stepper } from "@/components/ui/Stepper";
import type { AuctionStatus } from "@/types/auction";

const LABELS = ["Draft", "Paid", "Verifying", "Active"] as const;

/**
 * 4-step indicator for the activate flow. Mapping:
 *   - DRAFT                      → 0 (Draft)
 *   - DRAFT_PAID / VERIFICATION_FAILED → 1 (Paid)
 *   - VERIFICATION_PENDING       → 2 (Verifying)
 *   - anything later (ACTIVE...) → 3 (Active)
 *
 * VERIFICATION_FAILED pins the indicator back to Paid so the UI shows
 * "you're back on step 2" rather than still showing "Verifying" — this
 * is the "regression" visual in spec §5.1.
 */
export function statusToStepperIndex(status: AuctionStatus): number {
  if (status === "DRAFT") return 0;
  if (status === "DRAFT_PAID" || status === "VERIFICATION_FAILED") return 1;
  if (status === "VERIFICATION_PENDING") return 2;
  return 3;
}

export function ActivateStatusStepper({ status }: { status: AuctionStatus }) {
  return (
    <Stepper
      steps={LABELS as unknown as string[]}
      currentIndex={statusToStepperIndex(status)}
    />
  );
}
