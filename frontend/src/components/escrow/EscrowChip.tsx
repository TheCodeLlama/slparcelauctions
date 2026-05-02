import type { EscrowState } from "@/types/escrow";
import { cn } from "@/lib/cn";

export type EscrowChipRole = "seller" | "winner";
export type EscrowChipTone = "action" | "waiting" | "done" | "problem" | "muted";
export type EscrowChipSize = "sm" | "md";

export interface EscrowChipProps {
  state: EscrowState;
  /** Required to disambiguate TRANSFER_PENDING sub-phases. */
  transferConfirmedAt?: string | null;
  /** Omit for role-neutral labels (future admin contexts). */
  role?: EscrowChipRole;
  size?: EscrowChipSize;
  className?: string;
}

/**
 * State → (label, tone) mapping. See sub-spec 2 §3.4.
 *
 * TRANSFER_PENDING splits on transferConfirmedAt — post-confirmation both
 * roles see "Payout pending" (waiting tone) because the backend is
 * finalizing the transaction.
 */
function resolveChip(
  state: EscrowState,
  transferConfirmedAt: string | null | undefined,
  role: EscrowChipRole | undefined,
): { label: string; tone: EscrowChipTone } {
  switch (state) {
    case "ESCROW_PENDING":
      if (role === "winner") return { label: "Pay escrow", tone: "action" };
      if (role === "seller") return { label: "Awaiting payment", tone: "action" };
      return { label: "Escrow pending", tone: "action" };
    case "FUNDED":
    case "TRANSFER_PENDING":
      if (transferConfirmedAt) {
        return { label: "Payout pending", tone: "waiting" };
      }
      if (role === "winner") return { label: "Awaiting transfer", tone: "waiting" };
      if (role === "seller") return { label: "Transfer land", tone: "action" };
      return { label: "Transfer pending", tone: "waiting" };
    case "COMPLETED":
      return { label: "Completed", tone: "done" };
    case "DISPUTED":
      return { label: "Disputed", tone: "problem" };
    case "FROZEN":
      return { label: "Frozen", tone: "problem" };
    case "EXPIRED":
      return { label: "Expired", tone: "muted" };
  }
}

const toneClasses: Record<EscrowChipTone, string> = {
  action: "bg-brand-soft text-brand",
  waiting: "bg-bg-muted text-fg-muted",
  done: "bg-info-bg text-fg",
  problem: "bg-danger-bg text-danger-flat",
  muted: "bg-bg-muted text-fg-muted",
};

const sizeClasses: Record<EscrowChipSize, string> = {
  sm: "px-2 py-0.5 text-[11px] font-medium",
  md: "px-3 py-1 text-xs font-medium",
};

/**
 * Compact state badge surfaced across dashboard rows, auction detail banners,
 * and the escrow page header. Derives label + tone from `(state, role,
 * transferConfirmedAt)` so every call site speaks the same visual language.
 */
export function EscrowChip({
  state,
  transferConfirmedAt,
  role,
  size = "sm",
  className,
}: EscrowChipProps) {
  const { label, tone } = resolveChip(state, transferConfirmedAt, role);
  return (
    <span
      data-tone={tone}
      className={cn(
        "inline-flex items-center rounded-full font-semibold uppercase tracking-wide",
        toneClasses[tone],
        sizeClasses[size],
        className,
      )}
    >
      {label}
    </span>
  );
}
