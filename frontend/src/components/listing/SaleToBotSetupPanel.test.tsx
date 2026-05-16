import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { SaleToBotSetupPanel } from "./SaleToBotSetupPanel";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/listings/42/activate",
  useSearchParams: () => new URLSearchParams(),
}));

describe("SaleToBotSetupPanel", () => {
  it("renders the setup steps and the Verify button", () => {
    renderWithProviders(
      <SaleToBotSetupPanel
        auctionPublicId="00000000-0000-0000-0000-00000000002a"
        onBack={vi.fn()}
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByRole("heading", {
        name: /Set your land for sale to SLPAEscrow Resident/i,
      }),
    ).toBeInTheDocument();
    // The heading and the buyer step both mention SLPAEscrow Resident.
    expect(screen.getAllByText(/SLPAEscrow Resident/).length).toBeGreaterThan(0);
    expect(screen.getByText(/L\$999,999,999/)).toBeInTheDocument();
    expect(screen.getByTestId("sale-to-bot-setup-verify")).toBeInTheDocument();
    expect(screen.getByTestId("sale-to-bot-setup-back")).toBeInTheDocument();
  });

  it("clicking Verify fires triggerVerify with SALE_TO_BOT", async () => {
    let received: string | null = null;
    server.use(
      http.put(
        "*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify",
        async ({ request }) => {
          const body = (await request.json()) as { method: string };
          received = body.method;
          return HttpResponse.json({
            id: 42,
            status: "VERIFICATION_PENDING",
            verificationMethod: body.method,
            pendingVerification: null,
            parcel: {},
          });
        },
      ),
    );
    renderWithProviders(
      <SaleToBotSetupPanel
        auctionPublicId="00000000-0000-0000-0000-00000000002a"
        onBack={vi.fn()}
      />,
      { auth: "authenticated" },
    );
    await userEvent.click(screen.getByTestId("sale-to-bot-setup-verify"));
    await waitFor(() => expect(received).toBe("SALE_TO_BOT"));
  });

  it("clicking the back button calls onBack and does NOT fire verify", async () => {
    let verifyCalls = 0;
    server.use(
      http.put(
        "*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify",
        () => {
          verifyCalls += 1;
          return HttpResponse.json({});
        },
      ),
    );
    const onBack = vi.fn();
    renderWithProviders(
      <SaleToBotSetupPanel
        auctionPublicId="00000000-0000-0000-0000-00000000002a"
        onBack={onBack}
      />,
      { auth: "authenticated" },
    );
    await userEvent.click(screen.getByTestId("sale-to-bot-setup-back"));
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(verifyCalls).toBe(0);
  });
});
