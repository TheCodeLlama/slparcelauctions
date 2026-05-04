import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { SellerEvidencePanel } from "./SellerEvidencePanel";

describe("SellerEvidencePanel", () => {
  it("disables submit when text under 10 chars", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SellerEvidencePanel auctionPublicId="00000000-0000-0000-0000-000000000064" />, {
      auth: "authenticated",
    });
    const button = screen.getByRole("button", { name: /submit evidence/i });
    expect(button).toBeDisabled();

    await user.type(
      screen.getByPlaceholderText(/Describe what happened/),
      "short",
    );
    expect(button).toBeDisabled();

    await user.type(
      screen.getByPlaceholderText(/Describe what happened/),
      " more here",
    );
    expect(button).not.toBeDisabled();
  });

  it("submits multipart POST on click", async () => {
    const user = userEvent.setup();
    let received = false;
    server.use(
      http.post(
        "*/api/v1/auctions/00000000-0000-0000-0000-000000000064/escrow/dispute/seller-evidence",
        async () => {
          received = true;
          return HttpResponse.json({});
        },
      ),
    );
    renderWithProviders(<SellerEvidencePanel auctionPublicId="00000000-0000-0000-0000-000000000064" />, {
      auth: "authenticated",
    });
    await user.type(
      screen.getByPlaceholderText(/Describe what happened/),
      "I transferred the parcel at 14:30 — receipt attached",
    );
    await user.click(screen.getByRole("button", { name: /submit evidence/i }));
    await waitFor(() => {
      expect(received).toBe(true);
    });
  });

  it("shows error message on submit failure", async () => {
    const user = userEvent.setup();
    server.use(
      http.post(
        "*/api/v1/auctions/00000000-0000-0000-0000-000000000064/escrow/dispute/seller-evidence",
        async () => {
          return HttpResponse.json(
            { status: 500, title: "Internal Server Error" },
            { status: 500 },
          );
        },
      ),
    );
    renderWithProviders(<SellerEvidencePanel auctionPublicId="00000000-0000-0000-0000-000000000064" />, {
      auth: "authenticated",
    });
    await user.type(
      screen.getByPlaceholderText(/Describe what happened/),
      "I transferred the parcel at 14:30 — receipt attached",
    );
    await user.click(screen.getByRole("button", { name: /submit evidence/i }));
    expect(await screen.findByText(/submit failed/i)).toBeInTheDocument();
  });

  it("renders the upload area and character counter", () => {
    renderWithProviders(<SellerEvidencePanel auctionPublicId="00000000-0000-0000-0000-000000000064" />, {
      auth: "authenticated",
    });
    expect(screen.getByText(/0 \/ 2000/i)).toBeInTheDocument();
    // DisputeEvidenceUploader label
    expect(screen.getByText(/images/i)).toBeInTheDocument();
  });
});
