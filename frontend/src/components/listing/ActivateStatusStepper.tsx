import { Stepper } from "@/components/ui/Stepper";
import type { AuctionStatus } from "@/types/auction";

const LABELS = ["Draft", "Paid", "Active"] as const;

/**
 * 3-step indicator for the activate flow. Mapping:
 *   - DRAFT                                           → 0 (Draft)
 *   - DRAFT_PAID / VERIFICATION_FAILED / VERIFICATION_PENDING → 1 (Paid)
 *   - anything later (ACTIVE...)                      → 2 (Active)
 *
 * VERIFICATION_FAILED pins the indicator back to Paid so the UI shows
 * "you're back on step 2" rather than skipping ahead — this is the
 * "regression" visual in spec §5.
 *
 * VERIFICATION_PENDING is only reachable via legacy historical rows now
 * that ownership verification is a synchronous backend call; it also
 * sits on Paid since there's no separate Verifying step.
 */
export function statusToStepperIndex(status: AuctionStatus): number {
  if (status === "DRAFT") return 0;
  if (
    status === "DRAFT_PAID" ||
    status === "VERIFICATION_FAILED" ||
    status === "VERIFICATION_PENDING"
  ) {
    return 1;
  }
  return 2;
}

export function ActivateStatusStepper({ status }: { status: AuctionStatus }) {
  return (
    <Stepper
      steps={LABELS as unknown as string[]}
      currentIndex={statusToStepperIndex(status)}
    />
  );
}
