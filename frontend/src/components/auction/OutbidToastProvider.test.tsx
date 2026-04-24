import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { BidSettlementEnvelope, BidHistoryEntry } from "@/types/auction";
import type { ToastPayload } from "@/components/ui/Toast";
import {
  OutbidToastProvider,
  type AuctionSnapshot,
  type OutbidToastHandle,
} from "./OutbidToastProvider";

function fakeToast(): OutbidToastHandle & {
  warning: ReturnType<typeof vi.fn>;
} {
  const warningMock: OutbidToastHandle["warning"] & ReturnType<typeof vi.fn> =
    vi.fn((payload: string | ToastPayload) => {
      void payload;
    });
  return { warning: warningMock };
}

function envelope(
  overrides: Partial<BidSettlementEnvelope> = {},
): BidSettlementEnvelope {
  const newBids: BidHistoryEntry[] = [];
  return {
    type: "BID_SETTLEMENT",
    auctionId: 7,
    serverTime: "2026-04-20T12:30:00Z",
    currentBid: 2000,
    currentBidderId: 55,
    currentBidderDisplayName: "Bob",
    bidCount: 4,
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    newBids,
    ...overrides,
  };
}

describe("OutbidToastProvider.maybeFire", () => {
  let scrollSpy: ReturnType<typeof vi.fn>;
  let origScroll: typeof HTMLElement.prototype.scrollIntoView | undefined;

  beforeEach(() => {
    scrollSpy = vi.fn();
    // JSDOM / happy-dom don't implement scrollIntoView; stub it so the
    // action-button click path can observe whether the helper was invoked.
    origScroll = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView =
      scrollSpy as unknown as typeof HTMLElement.prototype.scrollIntoView;
    document.body.innerHTML = "";
  });

  afterEach(() => {
    if (origScroll) {
      HTMLElement.prototype.scrollIntoView = origScroll;
    }
    document.body.innerHTML = "";
  });

  it("fires toast.warning with structured payload when the caller was winning and is now losing", () => {
    const toast = fakeToast();
    const prev: AuctionSnapshot = { currentBidderId: 42 };

    OutbidToastProvider.maybeFire(
      prev,
      envelope({ currentBidderId: 55, currentBid: 2000 }),
      42,
      toast,
    );

    expect(toast.warning).toHaveBeenCalledTimes(1);
    const payload = toast.warning.mock.calls[0][0] as ToastPayload;
    expect(payload.title).toBe("You've been outbid");
    expect(payload.description).toBe("Current bid is L$2,000.");
    expect(payload.action).toBeDefined();
    expect(payload.action!.label).toBe("Place a new bid");
  });

  it("does not scroll on fire — scroll is deferred to the action-button onClick", () => {
    const slot = document.createElement("div");
    slot.setAttribute("data-testid", "bid-panel-slot");
    document.body.appendChild(slot);

    OutbidToastProvider.maybeFire(
      { currentBidderId: 42 },
      envelope({ currentBidderId: 55 }),
      42,
      fakeToast(),
    );

    expect(scrollSpy).not.toHaveBeenCalled();
  });

  it("the action-button onClick scrolls the bid panel into view", () => {
    const slot = document.createElement("div");
    slot.setAttribute("data-testid", "bid-panel-slot");
    document.body.appendChild(slot);

    const toast = fakeToast();
    OutbidToastProvider.maybeFire(
      { currentBidderId: 42 },
      envelope({ currentBidderId: 55 }),
      42,
      toast,
    );

    const payload = toast.warning.mock.calls[0][0] as ToastPayload;
    payload.action!.onClick();
    expect(scrollSpy).toHaveBeenCalledTimes(1);
  });

  it("does NOT fire when the caller was not winning", () => {
    const toast = fakeToast();
    OutbidToastProvider.maybeFire(
      { currentBidderId: 99 }, // previously someone else was winning
      envelope({ currentBidderId: 55 }),
      42,
      toast,
    );
    expect(toast.warning).not.toHaveBeenCalled();
  });

  it("does NOT fire when the caller is still winning (duplicate envelope)", () => {
    const toast = fakeToast();
    OutbidToastProvider.maybeFire(
      { currentBidderId: 42 },
      envelope({ currentBidderId: 42 }),
      42,
      toast,
    );
    expect(toast.warning).not.toHaveBeenCalled();
  });

  it("does NOT fire when currentUserId is null (anonymous viewer)", () => {
    const toast = fakeToast();
    OutbidToastProvider.maybeFire(
      { currentBidderId: 42 },
      envelope({ currentBidderId: 55 }),
      null,
      toast,
    );
    expect(toast.warning).not.toHaveBeenCalled();
  });

  it("does NOT fire when prevAuction is undefined (first envelope on stale cache)", () => {
    const toast = fakeToast();
    OutbidToastProvider.maybeFire(
      undefined,
      envelope({ currentBidderId: 55 }),
      42,
      toast,
    );
    expect(toast.warning).not.toHaveBeenCalled();
  });

  it("does NOT fire when prevAuction has no currentBidderId yet", () => {
    const toast = fakeToast();
    // Fresh page, no WS envelope yet — the seeded PublicAuctionResponse
    // doesn't carry currentBidderId, so the cache entry resolves to
    // {undefined === 42} → false, which must NOT trip the outbid toast
    // on the very first envelope.
    OutbidToastProvider.maybeFire(
      {},
      envelope({ currentBidderId: 55 }),
      42,
      toast,
    );
    expect(toast.warning).not.toHaveBeenCalled();
  });
});
