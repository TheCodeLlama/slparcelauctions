import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import type {
  CancellationHistoryDto,
  CancellationStatusResponse,
} from "@/types/cancellation";
import type { Page } from "@/types/page";
import {
  getCancellationHistory,
  getCancellationStatus,
} from "./cancellations";

function makeStatus(
  overrides: Partial<CancellationStatusResponse> = {},
): CancellationStatusResponse {
  return {
    priorOffensesWithBids: 0,
    currentSuspension: {
      penaltyBalanceOwed: 0,
      listingSuspensionUntil: null,
      bannedFromListing: false,
    },
    nextConsequenceIfBidsPresent: {
      kind: "WARNING",
      amountL: null,
      suspends30Days: false,
      permanentBan: false,
    },
    ...overrides,
  };
}

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

describe("getCancellationStatus", () => {
  it("hits /api/v1/users/me/cancellation-status and returns the envelope", async () => {
    const expected = makeStatus({
      priorOffensesWithBids: 2,
      currentSuspension: {
        penaltyBalanceOwed: 1500,
        listingSuspensionUntil: "2026-05-20T00:00:00Z",
        bannedFromListing: false,
      },
      nextConsequenceIfBidsPresent: {
        kind: "PENALTY_AND_30D",
        amountL: 2500,
        suspends30Days: true,
        permanentBan: false,
      },
    });
    server.use(
      http.get("*/api/v1/users/me/cancellation-status", () =>
        HttpResponse.json(expected),
      ),
    );

    const result = await getCancellationStatus();
    expect(result).toEqual(expected);
  });
});

describe("getCancellationHistory", () => {
  it("defaults to page=0 size=10 when called without args", async () => {
    let receivedUrl: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", ({ request }) => {
        receivedUrl = new URL(request.url);
        return HttpResponse.json(makePage());
      }),
    );

    await getCancellationHistory();

    expect(receivedUrl).not.toBeNull();
    expect(receivedUrl!.searchParams.get("page")).toBe("0");
    expect(receivedUrl!.searchParams.get("size")).toBe("10");
  });

  it("passes through page + size args as query params", async () => {
    let receivedUrl: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", ({ request }) => {
        receivedUrl = new URL(request.url);
        return HttpResponse.json(makePage());
      }),
    );

    await getCancellationHistory(2, 5);

    expect(receivedUrl!.searchParams.get("page")).toBe("2");
    expect(receivedUrl!.searchParams.get("size")).toBe("5");
  });

  it("returns the deserialized Page envelope verbatim", async () => {
    const rows = [
      makeRow({ auctionId: 1 }),
      makeRow({
        auctionId: 2,
        penaltyApplied: null,
        cancelledFromStatus: "DRAFT_PAID",
        hadBids: false,
      }),
    ];
    const expected = makePage(rows, { totalElements: 12, totalPages: 2 });
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", () =>
        HttpResponse.json(expected),
      ),
    );

    const result = await getCancellationHistory(0, 10);
    expect(result).toEqual(expected);
  });
});
