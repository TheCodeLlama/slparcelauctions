import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import { cancellationKeys, useCancellationStatus } from "./useCancellationStatus";

describe("cancellationKeys", () => {
  it("exposes a stable key for status", () => {
    expect(cancellationKeys.status).toEqual(["me", "cancellation-status"]);
  });

  it("composes history keys with page + size", () => {
    expect(cancellationKeys.historyAll).toEqual(["me", "cancellation-history"]);
    expect(cancellationKeys.history(0, 10)).toEqual([
      "me",
      "cancellation-history",
      0,
      10,
    ]);
    expect(cancellationKeys.history(2, 5)).toEqual([
      "me",
      "cancellation-history",
      2,
      5,
    ]);
  });
});

describe("useCancellationStatus", () => {
  it("fetches the status envelope and surfaces it via query.data", async () => {
    server.use(
      http.get("*/api/v1/users/me/cancellation-status", () =>
        HttpResponse.json({
          priorOffensesWithBids: 1,
          currentSuspension: {
            penaltyBalanceOwed: 1000,
            listingSuspensionUntil: null,
            bannedFromListing: false,
          },
          nextConsequenceIfBidsPresent: {
            kind: "PENALTY_AND_30D",
            amountL: 2500,
            suspends30Days: true,
            permanentBan: false,
          },
        }),
      ),
    );

    const { result } = renderHook(() => useCancellationStatus(), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.priorOffensesWithBids).toBe(1);
    expect(result.current.data?.currentSuspension.penaltyBalanceOwed).toBe(
      1000,
    );
    expect(result.current.data?.nextConsequenceIfBidsPresent.kind).toBe(
      "PENALTY_AND_30D",
    );
  });
});
