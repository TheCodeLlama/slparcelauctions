import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { ProxyBidSection } from "./ProxyBidSection";
import type {
  ProxyBidResponse,
  PublicAuctionResponse,
} from "@/types/auction";

vi.mock("@/lib/ws/client", () => ({
  subscribe: vi.fn(),
  subscribeToConnectionState: vi.fn(),
  getConnectionState: vi.fn(() => ({ status: "connected" })),
}));

function auctionFixture(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    id: 7,
    sellerId: 100,
    parcel: {
      id: 1,
      slParcelUuid: "00000000-0000-0000-0000-000000000001",
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 0,
      gridY: 0,
      continentName: null,
      areaSqm: 1024,
      description: "Beachfront parcel",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

function proxy(
  overrides: Partial<ProxyBidResponse> = {},
): ProxyBidResponse {
  return {
    proxyBidId: 1,
    auctionId: 7,
    maxAmount: 3000,
    status: "ACTIVE",
    createdAt: "2026-04-20T00:00:00Z",
    updatedAt: "2026-04-20T00:00:00Z",
    ...overrides,
  };
}

const connected = { status: "connected" as const };

describe("ProxyBidSection", () => {
  beforeEach(() => {
    if (typeof window !== "undefined") {
      window.sessionStorage.clear();
    }
  });

  it("renders the create form when there is no existing proxy", () => {
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={null}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    const section = screen.getByTestId("proxy-bid-section");
    expect(section).toHaveAttribute("data-mode", "create");
    expect(screen.getByTestId("proxy-bid-submit")).toHaveTextContent(
      /Set max bid/i,
    );
  });

  it("renders update + cancel buttons when ACTIVE and NOT winning", () => {
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={proxy({ status: "ACTIVE" })}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("proxy-active-callout")).toHaveTextContent(
      "L$3,000",
    );
    expect(screen.getByTestId("proxy-bid-submit")).toHaveTextContent(
      /Update max/i,
    );
    expect(screen.getByTestId("proxy-bid-cancel")).not.toBeDisabled();
  });

  it("disables the cancel button when the caller is currently winning", () => {
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={proxy({ status: "ACTIVE" })}
        currentUserIsWinning
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("proxy-bid-cancel")).toBeDisabled();
  });

  it("renders the exhausted callout and increase-max button for EXHAUSTED proxies", () => {
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={proxy({ status: "EXHAUSTED", maxAmount: 1200 })}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("proxy-bid-section")).toHaveAttribute(
      "data-mode",
      "exhausted",
    );
    expect(screen.getByTestId("proxy-exhausted-callout")).toHaveTextContent(
      "L$1,200",
    );
    expect(screen.getByTestId("proxy-bid-submit")).toHaveTextContent(
      /Increase your max/i,
    );
  });

  it("fires the buy-now confirm dialog when maxAmount >= buyNowPrice", async () => {
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture({ buyNowPrice: 5_000 })}
        existingProxy={null}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId("proxy-bid-max-input");
    await userEvent.type(input, "5000");
    await userEvent.click(screen.getByTestId("proxy-bid-submit"));
    expect(await screen.findByTestId("confirm-bid-dialog")).toHaveTextContent(
      /immediate buy-now at L\$5,000/i,
    );
  });

  it("posts createProxy on successful submit and clears the input", async () => {
    let received: { maxAmount: number } | null = null;
    server.use(
      http.post("*/api/v1/auctions/7/proxy-bid", async ({ request }) => {
        received = (await request.json()) as { maxAmount: number };
        return HttpResponse.json({
          proxyBidId: 1,
          auctionId: 7,
          maxAmount: received.maxAmount,
          status: "ACTIVE",
          createdAt: "2026-04-20T00:00:00Z",
          updatedAt: "2026-04-20T00:00:00Z",
        });
      }),
    );
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={null}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "proxy-bid-max-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "2500");
    await userEvent.click(screen.getByTestId("proxy-bid-submit"));
    await waitFor(() => {
      expect(received).not.toBeNull();
    });
    expect(received!.maxAmount).toBe(2500);
    await waitFor(() => {
      expect(input.value).toBe("");
    });
  });

  it("surfaces INVALID_PROXY_MAX with the server-provided reason inline", async () => {
    server.use(
      http.post("*/api/v1/auctions/7/proxy-bid", () =>
        HttpResponse.json(
          {
            status: 400,
            title: "Invalid Proxy Max",
            detail: "Invalid max",
            code: "INVALID_PROXY_MAX",
            reason: "Max must exceed current bid",
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={null}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    await userEvent.type(screen.getByTestId("proxy-bid-max-input"), "100");
    await userEvent.click(screen.getByTestId("proxy-bid-submit"));
    await waitFor(() => {
      expect(
        screen.getByText(/Max must exceed current bid/i),
      ).toBeInTheDocument();
    });
  });

  it("inline error clears when user edits the input", async () => {
    server.use(
      http.post("*/api/v1/auctions/7/proxy-bid", () =>
        HttpResponse.json(
          {
            status: 400,
            title: "Invalid Proxy Max",
            detail: "Invalid max",
            code: "INVALID_PROXY_MAX",
            reason: "Max must exceed current bid",
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={null}
        currentUserIsWinning={false}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId("proxy-bid-max-input");
    await userEvent.type(input, "100");
    await userEvent.click(screen.getByTestId("proxy-bid-submit"));
    await waitFor(() => {
      expect(
        screen.getByText(/Max must exceed current bid/i),
      ).toBeInTheDocument();
    });
    // Typing a new value should clear the inline error immediately.
    await userEvent.type(input, "0");
    await waitFor(() => {
      expect(
        screen.queryByText(/Max must exceed current bid/i),
      ).not.toBeInTheDocument();
    });
  });

  it("disables submit and shows helper text when disconnected", async () => {
    renderWithProviders(
      <ProxyBidSection
        auction={auctionFixture()}
        existingProxy={null}
        currentUserIsWinning={false}
        connectionState={{ status: "reconnecting" }}
      />,
      { auth: "authenticated" },
    );
    await userEvent.type(screen.getByTestId("proxy-bid-max-input"), "2500");
    expect(screen.getByTestId("proxy-bid-submit")).toBeDisabled();
    expect(
      screen.getByTestId("proxy-bid-connection-helper"),
    ).toHaveTextContent(/Waiting for connection/i);
  });
});
