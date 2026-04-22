import type { EscrowStatusResponse } from "@/types/escrow";
import { Fragment } from "react";
import { CheckCircle2, AlertCircle } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface EscrowStepperProps {
  escrow: EscrowStatusResponse;
  className?: string;
}

type StepState = "complete" | "current" | "upcoming";

interface ResolvedNode {
  label: string;
  state: StepState;
  timestamp: string | null;
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
}

/**
 * Resolves the three stepper nodes (Payment, Transfer, Complete) for a
 * non-terminal escrow. Terminal non-happy states (DISPUTED, FROZEN, EXPIRED)
 * render via the interrupt path.
 */
function resolveNodes(escrow: EscrowStatusResponse): ResolvedNode[] {
  const { fundedAt, transferConfirmedAt, completedAt } = escrow;

  const paymentComplete = fundedAt != null;
  const transferComplete = transferConfirmedAt != null;
  const allComplete = completedAt != null;

  const paymentNode: ResolvedNode = {
    label: "Payment",
    state: paymentComplete ? "complete" : "current",
    timestamp: paymentComplete ? fundedAt : null,
  };

  const transferNode: ResolvedNode = {
    label: "Transfer",
    state: transferComplete
      ? "complete"
      : paymentComplete
        ? "current"
        : "upcoming",
    timestamp: transferComplete ? transferConfirmedAt : null,
  };

  const completeNode: ResolvedNode = {
    label: "Complete",
    state: allComplete
      ? "complete"
      : transferComplete
        ? "current"
        : "upcoming",
    timestamp: allComplete ? completedAt : null,
  };

  return [paymentNode, transferNode, completeNode];
}

type InterruptInfo = {
  label: string;
  timestamp: string | null;
  precedingNodes: ResolvedNode[];
};

function resolveInterrupt(
  escrow: EscrowStatusResponse,
): InterruptInfo | null {
  const { state, fundedAt, transferConfirmedAt } = escrow;
  const paymentNode: ResolvedNode = {
    label: "Payment",
    state: fundedAt ? "complete" : "upcoming",
    timestamp: fundedAt,
  };
  const transferNode: ResolvedNode = {
    label: "Transfer",
    state: transferConfirmedAt ? "complete" : "upcoming",
    timestamp: transferConfirmedAt,
  };
  const preceding = [paymentNode, transferNode].filter(
    (n) => n.state === "complete",
  );

  if (state === "DISPUTED") {
    return {
      label: "Disputed",
      timestamp: escrow.disputedAt,
      precedingNodes: preceding,
    };
  }
  if (state === "FROZEN") {
    return {
      label: "Frozen",
      timestamp: escrow.frozenAt,
      precedingNodes: preceding,
    };
  }
  if (state === "EXPIRED") {
    return {
      label: "Expired",
      timestamp: escrow.expiredAt,
      precedingNodes: preceding,
    };
  }
  return null;
}

/**
 * Three-node happy-path stepper (Payment → Transfer → Complete) with a
 * collapse-to-interrupt rendering for terminal non-happy states (DISPUTED,
 * FROZEN, EXPIRED). Preserves completed nodes preceding the interrupt so the
 * viewer can see what phase the escrow reached before it halted.
 */
export function EscrowStepper({ escrow, className }: EscrowStepperProps) {
  const interrupt = resolveInterrupt(escrow);

  if (interrupt) {
    return (
      <ol className={cn("flex items-center gap-3 overflow-x-auto", className)}>
        {interrupt.precedingNodes.map((n, idx) => (
          <StepperNode key={n.label} node={n} index={idx} />
        ))}
        <li
          data-state="interrupt"
          className="flex flex-col items-center gap-1 text-error"
        >
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-error bg-error-container text-on-error-container">
            <AlertCircle className="size-3.5" aria-hidden="true" />
          </span>
          <span className="text-label-md font-semibold">{interrupt.label}</span>
          {interrupt.timestamp && (
            <span className="text-label-sm text-on-surface-variant">
              {formatTimestamp(interrupt.timestamp)}
            </span>
          )}
        </li>
      </ol>
    );
  }

  const nodes = resolveNodes(escrow);
  return (
    <ol className={cn("flex items-center gap-3 overflow-x-auto", className)}>
      {nodes.map((n, idx) => (
        <StepperNode
          key={n.label}
          node={n}
          index={idx}
          last={idx === nodes.length - 1}
        />
      ))}
    </ol>
  );
}

function StepperNode({
  node,
  index,
  last,
}: {
  node: ResolvedNode;
  index: number;
  last?: boolean;
}) {
  return (
    <Fragment>
      <li
        data-state={node.state}
        aria-current={node.state === "current" ? "step" : undefined}
        className={cn(
          "flex flex-col items-center gap-1",
          node.state === "complete" && "text-on-tertiary-container",
          node.state === "current" && "text-primary",
          node.state === "upcoming" && "text-on-surface-variant",
        )}
      >
        <span
          className={cn(
            "inline-flex h-6 w-6 items-center justify-center rounded-full border text-label-md",
            node.state === "complete" &&
              "border-tertiary bg-tertiary text-on-tertiary",
            node.state === "current" && "border-primary text-primary",
            node.state === "upcoming" &&
              "border-outline-variant text-on-surface-variant",
          )}
        >
          {node.state === "complete" ? (
            <CheckCircle2 className="size-3.5" aria-hidden="true" />
          ) : (
            index + 1
          )}
        </span>
        <span className="text-label-md font-semibold">{node.label}</span>
        {node.timestamp && (
          <span className="text-label-sm text-on-surface-variant">
            {formatTimestamp(node.timestamp)}
          </span>
        )}
      </li>
      {!last && (
        <span
          className="mx-1 h-px w-6 bg-outline-variant"
          aria-hidden="true"
        />
      )}
    </Fragment>
  );
}
