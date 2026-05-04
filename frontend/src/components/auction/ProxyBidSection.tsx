"use client";

import { useId, useState, type FocusEvent, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { useToast } from "@/components/ui/Toast";
import { ApiError, isApiError } from "@/lib/api";
import {
  cancelProxy,
  createProxy,
  updateProxy,
} from "@/lib/api/auctions";
import { myProxyKey } from "@/hooks/useMyProxy";
import type {
  ProxyBidResponse,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { ConnectionState } from "@/lib/ws/types";
import { ConfirmBidDialog } from "./ConfirmBidDialog";

export interface ProxyBidSectionProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  existingProxy: ProxyBidResponse | null;
  currentUserIsWinning: boolean;
  connectionState: ConnectionState;
}

type Mode = "create" | "active" | "exhausted";

type PendingAction =
  | { kind: "none" }
  | { kind: "buy-now-overspend"; maxAmount: number }
  | { kind: "cancel" };

/**
 * Proxy-bid ("auto-bid up to") control. Renders one of three branches
 * based on the caller's current proxy status:
 *
 * <ul>
 *   <li>{@code null | CANCELLED} → create form</li>
 *   <li>{@code ACTIVE}           → update + cancel (cancel disabled while
 *       winning)</li>
 *   <li>{@code EXHAUSTED}        → "outbid at L$X" callout + increase-max
 *       form (PUT reactivates per sub-spec 1 §7)</li>
 * </ul>
 *
 * Buy-now overspend guard fires for {@code maxAmount >= buyNowPrice} on
 * every branch — backend emits one bid at buyNowPrice and ends the
 * auction atomically per sub-spec 1 §6 step 6. Errors mirror spec §9.
 */
export function ProxyBidSection({
  auction,
  existingProxy,
  currentUserIsWinning,
  connectionState,
}: ProxyBidSectionProps) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const maxInputId = useId();
  const [inlineError, setInlineError] = useState<string | null>(null);
  const [maxInput, setMaxInput] = useState<string>("");
  const [pending, setPending] = useState<PendingAction>({ kind: "none" });

  const mode: Mode =
    existingProxy == null
      ? "create"
      : existingProxy.status === "ACTIVE"
      ? "active"
      : existingProxy.status === "EXHAUSTED"
      ? "exhausted"
      : "create"; // CANCELLED → show create form again

  const buyNow = auction.buyNowPrice;
  const parsed = maxInput === "" ? NaN : Number(maxInput);
  const isConnected = connectionState.status === "connected";

  const invalidateMyProxy = () => {
    queryClient.invalidateQueries({ queryKey: myProxyKey(auction.publicId) });
  };

  const mutateProxy = useMutation<ProxyBidResponse, unknown, number>({
    mutationFn: (value) =>
      mode === "create"
        ? createProxy(auction.publicId, value)
        : updateProxy(auction.publicId, value),
    onMutate: () => setInlineError(null),
    onSuccess: () => {
      invalidateMyProxy();
      setMaxInput("");
    },
    onError: handleProxyError,
  });

  const cancelProxyMut = useMutation<void, unknown, void>({
    mutationFn: () => cancelProxy(auction.publicId),
    onMutate: () => setInlineError(null),
    onSuccess: () => {
      invalidateMyProxy();
      toast.success("Proxy cancelled.");
    },
    onError: handleProxyError,
  });

  function handleProxyError(err: unknown) {
    if (err instanceof ApiError || isApiError(err)) {
      const code = err.problem.code as string | undefined;
      const reason = err.problem.reason as string | undefined;
      if (code === "BID_TOO_LOW") {
        setInlineError(
          err.problem.detail ?? "That max is below the minimum.",
        );
        return;
      }
      if (code === "PROXY_BID_ALREADY_EXISTS") {
        invalidateMyProxy();
        setInlineError(
          "You already have a proxy on this auction. Please try again.",
        );
        return;
      }
      if (code === "INVALID_PROXY_MAX" || code === "INVALID_PROXY_STATE") {
        setInlineError(reason ?? err.problem.detail ?? "Invalid proxy max.");
        return;
      }
      if (code === "CANNOT_CANCEL_WINNING_PROXY") {
        invalidateMyProxy();
        toast.error(
          "You can't cancel a proxy while you're winning the auction.",
        );
        return;
      }
      toast.error("Something went wrong. Please try again.");
      return;
    }
    toast.error("Something went wrong. Please try again.");
  }

  const submit = (value: number) => {
    mutateProxy.mutate(value);
  };

  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setInlineError("Enter a positive L$ amount.");
      return;
    }
    if (!isConnected || mutateProxy.isPending || cancelProxyMut.isPending) {
      return;
    }
    if (buyNow != null && parsed >= buyNow) {
      setPending({ kind: "buy-now-overspend", maxAmount: parsed });
      return;
    }
    submit(parsed);
  };

  const submitDisabled =
    !Number.isFinite(parsed) ||
    parsed <= 0 ||
    !isConnected ||
    mutateProxy.isPending;

  const cancelDisabled =
    currentUserIsWinning ||
    !isConnected ||
    cancelProxyMut.isPending;

  const submitLabel =
    mode === "create"
      ? "Set max bid"
      : mode === "active"
      ? "Update max"
      : "Increase your max";

  return (
    <div
      data-testid="proxy-bid-section"
      data-mode={mode}
      className="flex flex-col gap-3 rounded-xl bg-surface-raised p-4 shadow-sm"
    >
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-semibold text-fg">
          Auto-bid up to (proxy)
        </h3>
        {mode === "create" ? (
          <p className="text-xs text-fg-muted">
            Set your maximum — we&apos;ll bid for you up to that amount.
          </p>
        ) : null}
        {mode === "active" && existingProxy ? (
          <p
            className="text-xs text-fg-muted"
            data-testid="proxy-active-callout"
          >
            You have a proxy at L$
            {existingProxy.maxAmount.toLocaleString()} max.
          </p>
        ) : null}
        {mode === "exhausted" && existingProxy ? (
          <p
            className="text-xs text-fg-muted"
            data-testid="proxy-exhausted-callout"
          >
            You were outbid at L$
            {existingProxy.maxAmount.toLocaleString()}. Increase your max to
            re-enter the auction.
          </p>
        ) : null}
      </div>

      <form onSubmit={onSubmit} className="flex flex-col gap-3">
        <div>
          <label
            htmlFor={maxInputId}
            className="text-xs font-medium text-fg-muted"
          >
            Your max bid
          </label>
          <Input
            id={maxInputId}
            type="number"
            inputMode="numeric"
            min={1}
            step={1}
            value={maxInput}
            onChange={(e) => {
              setMaxInput(e.target.value);
              setInlineError(null);
            }}
            onFocus={scrollInputIntoView}
            placeholder="L$"
            leftIcon={<span className="text-xs font-medium">L$</span>}
            className="text-right"
            data-testid="proxy-bid-max-input"
            error={inlineError ?? undefined}
          />
        </div>
        <div className="flex items-center gap-2">
          <Button
            type="submit"
            variant="primary"
            fullWidth
            disabled={submitDisabled}
            loading={mutateProxy.isPending}
            data-testid="proxy-bid-submit"
          >
            {submitLabel}
          </Button>
          {mode === "active" ? (
            <Button
              type="button"
              variant="secondary"
              onClick={() => setPending({ kind: "cancel" })}
              disabled={cancelDisabled}
              data-testid="proxy-bid-cancel"
            >
              Cancel proxy
            </Button>
          ) : null}
        </div>
        {!isConnected ? (
          <p
            className="text-xs text-fg-muted"
            data-testid="proxy-bid-connection-helper"
          >
            Waiting for connection…
          </p>
        ) : null}
      </form>

      {pending.kind === "buy-now-overspend" && buyNow != null ? (
        <ConfirmBidDialog
          isOpen
          title={`Trigger buy-now at L$${buyNow.toLocaleString()}?`}
          message={`This max will trigger an immediate buy-now at L$${buyNow.toLocaleString()}.`}
          confirmLabel={`Buy now · L$${buyNow.toLocaleString()}`}
          onConfirm={() => {
            const amt = pending.maxAmount;
            setPending({ kind: "none" });
            submit(amt);
          }}
          onClose={() => setPending({ kind: "none" })}
        />
      ) : null}

      {pending.kind === "cancel" ? (
        <ConfirmBidDialog
          isOpen
          title="Cancel your proxy bid?"
          message="The system will stop auto-bidding for you."
          confirmLabel="Cancel proxy"
          cancelLabel="Keep proxy"
          onConfirm={() => {
            setPending({ kind: "none" });
            cancelProxyMut.mutate();
          }}
          onClose={() => setPending({ kind: "none" })}
        />
      ) : null}
    </div>
  );
}

/**
 * Scrolls the focused input into the vertical centre of the viewport —
 * spec §11's mobile-keyboard UX. iOS/Android soft keyboards cover the
 * lower half of the screen on focus; without this the submit button
 * ends up behind the keyboard. Guarded against environments where
 * {@code scrollIntoView} isn't implemented (JSDOM, happy-dom ≤ 17) so
 * focus events don't crash unit tests.
 */
function scrollInputIntoView(e: FocusEvent<HTMLInputElement>): void {
  const el = e.currentTarget;
  if (el instanceof HTMLElement && typeof el.scrollIntoView === "function") {
    el.scrollIntoView({ behavior: "smooth", block: "center" });
  }
}
