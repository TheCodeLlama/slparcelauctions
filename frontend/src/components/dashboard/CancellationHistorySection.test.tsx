import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { CancellationHistoryDto } from "@/types/cancellation";
import type { Page } from "@/types/page";
import { CancellationHistorySection } from "./CancellationHistorySection";

function makeRow(
  overrides: Partial<CancellationHistoryDto> = {},
): CancellationHistoryDto {
  return {
    auctionId: 1,
    auctionTitle: "Aurora Parcel",
    primaryPhotoUrl: null,
    cancelledFromStatus: "ACTIVE",
    hadBids: true,
    reason: "Buyer changed mind.",
    cancelledAt: "2026-04-20T10:00:00Z",
    penaltyApplied: { kind: "PENALTY", amountL: 1000 },
    ...overrides,
  };
}

function makePage(
  content: CancellationHistoryDto[] = [],
  overrides: Partial<Page<CancellationHistoryDto>> = {},
): Page<CancellationHistoryDto> {
  return {
    content,
    totalElements: content.length,
    totalPages: Math.max(1, Math.ceil(content.length / 10)),
    number: 0,
    size: 10,
    ...overrides,
  };
}

describe("CancellationHistorySection", () => {
  it("renders nothing while loading", () => {
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json(makePage());
      }),
    );
    renderWithProviders(<CancellationHistorySection />, {
      auth: "authenticated",
    });
    expect(
      screen.queryByTestId("cancellation-history-section"),
    ).not.toBeInTheDocument();
  });

  it("renders the empty-state card when there are no cancellations", async () => {
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", () =>
        HttpResponse.json(makePage()),
      ),
    );
    renderWithProviders(<CancellationHistorySection />, {
      auth: "authenticated",
    });
    const section = await screen.findByTestId("cancellation-history-section");
    expect(section.dataset.variant).toBe("empty");
    expect(section).toHaveTextContent(/no cancellations yet/i);
  });

  it("renders a row per cancellation with badge mapped from the kind", async () => {
    const rows = [
      makeRow({
        auctionId: 1,
        auctionTitle: "Aurora Parcel",
        penaltyApplied: { kind: "WARNING", amountL: null },
      }),
      makeRow({
        auctionId: 2,
        auctionTitle: "Lakeview Shore",
        penaltyApplied: { kind: "PENALTY_AND_30D", amountL: 2500 },
      }),
      makeRow({
        auctionId: 3,
        auctionTitle: "Skyhigh Plot",
        penaltyApplied: null,
      }),
    ];
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", () =>
        HttpResponse.json(makePage(rows)),
      ),
    );
    renderWithProviders(<CancellationHistorySection />, {
      auth: "authenticated",
    });

    expect(
      await screen.findByText("Aurora Parcel"),
    ).toBeInTheDocument();
    expect(screen.getByText("Lakeview Shore")).toBeInTheDocument();
    expect(screen.getByText("Skyhigh Plot")).toBeInTheDocument();

    const badges = screen.getAllByTestId("cancellation-consequence-badge");
    expect(badges).toHaveLength(3);
    expect(badges[0]).toHaveTextContent("Warning");
    expect(badges[1]).toHaveTextContent("L$2,500 + 30-day suspension");
    expect(badges[2]).toHaveTextContent("No penalty");
  });

  it("expands and collapses the reason via the toggle button", async () => {
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", () =>
        HttpResponse.json(makePage([makeRow({ reason: "Buyer changed mind." })])),
      ),
    );
    renderWithProviders(<CancellationHistorySection />, {
      auth: "authenticated",
    });

    const toggle = await screen.findByTestId("cancellation-reason-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByTestId("cancellation-reason-text"),
    ).not.toBeInTheDocument();

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByTestId("cancellation-reason-text")).toHaveTextContent(
      "Buyer changed mind.",
    );

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByTestId("cancellation-reason-text"),
    ).not.toBeInTheDocument();
  });

  it("does not render the reason toggle when the row has no reason", async () => {
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", () =>
        HttpResponse.json(makePage([makeRow({ reason: "" })])),
      ),
    );
    renderWithProviders(<CancellationHistorySection />, {
      auth: "authenticated",
    });
    await screen.findByText("Aurora Parcel");
    expect(
      screen.queryByTestId("cancellation-reason-toggle"),
    ).not.toBeInTheDocument();
  });

  it("paginates and refetches when the user clicks a different page", async () => {
    const pageZero = makePage(
      [makeRow({ auctionId: 1, auctionTitle: "Page 0 row" })],
      { totalElements: 12, totalPages: 2, number: 0, size: 10 },
    );
    const pageOne = makePage(
      [makeRow({ auctionId: 99, auctionTitle: "Page 1 row" })],
      { totalElements: 12, totalPages: 2, number: 1, size: 10 },
    );
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", ({ request }) => {
        const url = new URL(request.url);
        return HttpResponse.json(
          url.searchParams.get("page") === "1" ? pageOne : pageZero,
        );
      }),
    );

    renderWithProviders(<CancellationHistorySection />, {
      auth: "authenticated",
    });
    expect(await screen.findByText("Page 0 row")).toBeInTheDocument();

    const pageTwoButton = screen.getByRole("button", { name: /page 2/i });
    await userEvent.click(pageTwoButton);

    await waitFor(() =>
      expect(screen.getByText("Page 1 row")).toBeInTheDocument(),
    );
    expect(screen.queryByText("Page 0 row")).not.toBeInTheDocument();
  });
});
