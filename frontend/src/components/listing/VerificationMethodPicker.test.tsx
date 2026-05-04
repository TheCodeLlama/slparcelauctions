import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { VerificationMethodPicker } from "./VerificationMethodPicker";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/listings/42/activate",
  useSearchParams: () => new URLSearchParams(),
}));

describe("VerificationMethodPicker", () => {
  it("triggers verify with the clicked method", async () => {
    let received: string | null = null;
    server.use(
      http.put("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify", async ({ request }) => {
        const body = (await request.json()) as { method: string };
        received = body.method;
        return HttpResponse.json({
          id: 42,
          status: "VERIFICATION_PENDING",
          verificationMethod: body.method,
          pendingVerification: null,
          parcel: {},
        });
      }),
    );
    renderWithProviders(<VerificationMethodPicker auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });
    const buttons = await screen.findAllByRole("button", {
      name: /Use this method/i,
    });
    await userEvent.click(buttons[0]);
    await waitFor(() => expect(received).toBe("UUID_ENTRY"));
  });

  it("shows the failure banner with the notes when provided", () => {
    renderWithProviders(
      <VerificationMethodPicker
        auctionPublicId="00000000-0000-0000-0000-00000000002a"
        lastFailureNotes="Ownership check failed: SL API returned a different owner."
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByText(/Ownership check failed/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/no additional fee needed/i),
    ).toBeInTheDocument();
  });

  it("remaps a 422 error to the group-owned prescriptive message", async () => {
    server.use(
      http.put("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify", () =>
        HttpResponse.json(
          {
            status: 422,
            title: "Unprocessable Entity",
            detail: "Group-owned parcels require SALE_TO_BOT.",
          },
          { status: 422 },
        ),
      ),
    );
    renderWithProviders(<VerificationMethodPicker auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });
    const buttons = await screen.findAllByRole("button", {
      name: /Use this method/i,
    });
    await userEvent.click(buttons[0]);
    expect(
      await screen.findByText(/Pick Sale-to-bot instead/i),
    ).toBeInTheDocument();
  });
});
