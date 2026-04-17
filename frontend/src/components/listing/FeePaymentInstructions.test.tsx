import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { FeePaymentInstructions } from "./FeePaymentInstructions";

describe("FeePaymentInstructions", () => {
  it("renders the fee amount once the config resolves", async () => {
    server.use(
      http.get("*/api/v1/config/listing-fee", () =>
        HttpResponse.json({ amountLindens: 100 }),
      ),
    );
    renderWithProviders(<FeePaymentInstructions auctionId={42} />, {
      auth: "authenticated",
    });
    await waitFor(() =>
      expect(screen.getByText(/L\$100/)).toBeInTheDocument(),
    );
  });

  it("derives the short reference code from the auction id", () => {
    server.use(
      http.get("*/api/v1/config/listing-fee", () =>
        HttpResponse.json({ amountLindens: 100 }),
      ),
    );
    renderWithProviders(<FeePaymentInstructions auctionId={42} />, {
      auth: "authenticated",
    });
    // 42 → "00000042" → reference "LISTING-00000042"
    expect(screen.getByText("LISTING-00000042")).toBeInTheDocument();
  });
});
