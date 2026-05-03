import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { WalletTermsModal } from "./WalletTermsModal";

describe("WalletTermsModal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the ToU body when open", () => {
    renderWithProviders(
      <WalletTermsModal open={true} onClose={vi.fn()} />,
    );
    expect(
      screen.getByText(/SLPA Wallet Terms of Use/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/Non-interest-bearing/i)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^I Accept$/i }),
    ).toBeInTheDocument();
  });

  it("renders nothing when closed", () => {
    renderWithProviders(
      <WalletTermsModal open={false} onClose={vi.fn()} />,
    );
    expect(
      screen.queryByText(/SLPA Wallet Terms of Use/i),
    ).not.toBeInTheDocument();
  });

  it("calls accept-terms, closes, and fires onAccepted on I Accept", async () => {
    let posted = false;
    server.use(
      http.post("*/api/v1/me/wallet/accept-terms", () => {
        posted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const onClose = vi.fn();
    const onAccepted = vi.fn();
    renderWithProviders(
      <WalletTermsModal
        open={true}
        onClose={onClose}
        onAccepted={onAccepted}
      />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /^I Accept$/i }),
    );
    await waitFor(() => expect(posted).toBe(true));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(onAccepted).toHaveBeenCalledTimes(1);
  });

  it("calls onClose on Cancel without posting", async () => {
    let posted = false;
    server.use(
      http.post("*/api/v1/me/wallet/accept-terms", () => {
        posted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const onClose = vi.fn();
    renderWithProviders(
      <WalletTermsModal open={true} onClose={onClose} />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /^Cancel$/i }),
    );
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(posted).toBe(false);
  });

  it("surfaces a server error inline and does not close", async () => {
    server.use(
      http.post("*/api/v1/me/wallet/accept-terms", () =>
        HttpResponse.json(
          {
            status: 500,
            title: "Internal Server Error",
            detail: "Database is on fire.",
          },
          { status: 500 },
        ),
      ),
    );
    const onClose = vi.fn();
    renderWithProviders(
      <WalletTermsModal open={true} onClose={onClose} />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /^I Accept$/i }),
    );
    expect(
      await screen.findByText(/Database is on fire/i),
    ).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });
});
