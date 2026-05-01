import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement, ReactNode } from "react";

import type { WalletView } from "@/types/wallet";
import { walletQueryKey } from "./use-wallet";
import {
  useWalletWsSubscription,
  type WalletBalanceChangedEnvelope,
} from "./use-wallet-ws";

// ---------------------------------------------------------------------------
// WS module mock — capture each subscribe(destination, callback) call so the
// tests can drive envelopes through the registered handler. Mirrors the
// pattern in `useNotificationStream.test.tsx`.
// ---------------------------------------------------------------------------
const { subscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
}));

vi.mock("@/lib/ws/client", () => ({
  subscribe: (...args: unknown[]) => subscribeMock(...args),
  subscribeToConnectionState: () => () => {},
  getConnectionState: () => ({ status: "connected" as const }),
}));

function makeWrapper(): {
  client: QueryClient;
  wrapper: (props: { children: ReactNode }) => ReactElement;
} {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }): ReactElement => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

function captureSubscriptions() {
  const map = new Map<string, (payload: unknown) => void>();
  subscribeMock.mockImplementation(
    (destination: string, cb: (payload: unknown) => void) => {
      map.set(destination, cb);
      return () => {};
    },
  );
  return map;
}

function makeWallet(partial: Partial<WalletView> = {}): WalletView {
  return {
    balance: 1000,
    reserved: 0,
    available: 1000,
    penaltyOwed: 0,
    queuedForWithdrawal: 0,
    termsAccepted: true,
    termsVersion: "1.0",
    termsAcceptedAt: "2026-04-01T00:00:00Z",
    recentLedger: [],
    ...partial,
  };
}

describe("useWalletWsSubscription", () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    subscribeMock.mockImplementation(() => () => {});
  });

  it("subscribes to /user/queue/wallet when enabled", () => {
    const { wrapper } = makeWrapper();
    renderHook(() => useWalletWsSubscription(true), { wrapper });

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock.mock.calls[0][0]).toBe("/user/queue/wallet");
  });

  it("does not subscribe when disabled (empty destination short-circuit)", () => {
    const { wrapper } = makeWrapper();
    renderHook(() => useWalletWsSubscription(false), { wrapper });

    // Empty destination is the no-op convention in useStompSubscription —
    // the underlying `subscribe()` is never called.
    expect(subscribeMock).not.toHaveBeenCalled();
  });

  it("patches balance fields in the wallet cache on incoming envelope", () => {
    const subs = captureSubscriptions();
    const { client, wrapper } = makeWrapper();

    client.setQueryData<WalletView>(
      walletQueryKey,
      makeWallet({ balance: 1000, reserved: 0, available: 1000, penaltyOwed: 0 }),
    );

    renderHook(() => useWalletWsSubscription(true), { wrapper });

    const env: WalletBalanceChangedEnvelope = {
      balance: 1500,
      reserved: 500,
      available: 1000,
      penaltyOwed: 0,
      queuedForWithdrawal: 0,
      reason: "DEPOSIT",
      ledgerEntryId: 42,
      occurredAt: "2026-05-01T12:00:00Z",
    };
    act(() => {
      subs.get("/user/queue/wallet")?.(env);
    });

    const updated = client.getQueryData<WalletView>(walletQueryKey);
    expect(updated?.balance).toBe(1500);
    expect(updated?.reserved).toBe(500);
    expect(updated?.available).toBe(1000);
    expect(updated?.penaltyOwed).toBe(0);
    // Non-balance fields preserved.
    expect(updated?.termsAccepted).toBe(true);
    expect(updated?.termsVersion).toBe("1.0");
  });

  it("leaves the cache untouched when no prior wallet snapshot exists", () => {
    const subs = captureSubscriptions();
    const { client, wrapper } = makeWrapper();

    renderHook(() => useWalletWsSubscription(true), { wrapper });

    const env: WalletBalanceChangedEnvelope = {
      balance: 1500,
      reserved: 500,
      available: 1000,
      penaltyOwed: 0,
      queuedForWithdrawal: 0,
      reason: "DEPOSIT",
      ledgerEntryId: 42,
      occurredAt: "2026-05-01T12:00:00Z",
    };
    act(() => {
      subs.get("/user/queue/wallet")?.(env);
    });

    // No snapshot present, hook returns prev (undefined) unchanged so the
    // next useWallet() fetch will hydrate authoritatively.
    expect(client.getQueryData(walletQueryKey)).toBeUndefined();
  });

  it("invalidates ledger queries on incoming envelope", () => {
    const subs = captureSubscriptions();
    const { client, wrapper } = makeWrapper();

    const ledgerKey = ["me", "wallet", "ledger", {}, 0, 25] as const;
    client.setQueryData(ledgerKey, {
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 25,
    });

    renderHook(() => useWalletWsSubscription(true), { wrapper });

    const env: WalletBalanceChangedEnvelope = {
      balance: 1500,
      reserved: 0,
      available: 1500,
      penaltyOwed: 0,
      queuedForWithdrawal: 0,
      reason: "DEPOSIT",
      ledgerEntryId: 42,
      occurredAt: "2026-05-01T12:00:00Z",
    };
    act(() => {
      subs.get("/user/queue/wallet")?.(env);
    });

    expect(client.getQueryState(ledgerKey)?.isInvalidated).toBe(true);
  });

  it("idempotent across two simultaneous subscribers", () => {
    // Each subscribe() call produces an independent callback closure — capture
    // all of them so the test can fan out one envelope to every subscriber the
    // way the real STOMP client does.
    const callbacks: Array<(payload: unknown) => void> = [];
    subscribeMock.mockImplementation(
      (_destination: string, cb: (payload: unknown) => void) => {
        callbacks.push(cb);
        return () => {};
      },
    );

    const { client, wrapper } = makeWrapper();

    client.setQueryData<WalletView>(
      walletQueryKey,
      makeWallet({ balance: 1000, reserved: 0, available: 1000 }),
    );

    // Two mounts simulate the indicator + panel both being on screen.
    renderHook(() => useWalletWsSubscription(true), { wrapper });
    renderHook(() => useWalletWsSubscription(true), { wrapper });

    expect(subscribeMock).toHaveBeenCalledTimes(2);
    expect(callbacks).toHaveLength(2);

    // Fan one envelope out to both subscribers — both writes should land on
    // the same merged cache state.
    const env: WalletBalanceChangedEnvelope = {
      balance: 1500,
      reserved: 500,
      available: 1000,
      penaltyOwed: 0,
      queuedForWithdrawal: 0,
      reason: "BID_RESERVED",
      ledgerEntryId: 7,
      occurredAt: "2026-05-01T12:00:00Z",
    };
    act(() => {
      for (const cb of callbacks) cb(env);
    });

    const updated = client.getQueryData<WalletView>(walletQueryKey);
    expect(updated?.balance).toBe(1500);
    expect(updated?.reserved).toBe(500);
    expect(updated?.available).toBe(1000);
    expect(updated?.penaltyOwed).toBe(0);
    // Non-balance state preserved through both merges.
    expect(updated?.termsAccepted).toBe(true);
  });
});
