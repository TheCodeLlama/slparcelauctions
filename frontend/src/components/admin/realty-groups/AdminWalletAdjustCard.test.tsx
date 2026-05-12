import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { AdminWalletAdjustCard } from "./AdminWalletAdjustCard";

const GROUP_ID = "00000000-0000-4000-8000-000000000001";
const ADJUST_PATH = `*/api/v1/admin/realty-groups/${GROUP_ID}/wallet/adjust`;

type AdjustBody = { amount: number; reason: string };

/**
 * Sub-project G §7.2 — Vitest for {@link AdminWalletAdjustCard}.
 *
 * <p>The MSW handlers echo the request body so each test can assert the
 * exact signed amount + reason that hit the wire. The default handler
 * returns a {@link GroupWallet}-shaped payload sourced from the request
 * body so the {@code onAdjusted} callback receives the right wire shape.
 */
function echoHandler() {
  return http.post(ADJUST_PATH, async ({ request }) => {
    const body = (await request.json()) as AdjustBody;
    return HttpResponse.json({
      balance: body.amount,
      reserved: 0,
      available: body.amount,
      leaderTermsAcceptedAt: null,
      recentLedger: [],
    });
  });
}

describe("AdminWalletAdjustCard", () => {
  it("submits a positive (credit) amount with the typed reason", async () => {
    server.use(echoHandler());
    const onAdjusted = vi.fn();
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={onAdjusted} />,
    );

    // Credit is the default direction; type amount + reason and submit.
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "2500",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Compensating bad payout",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() => expect(onAdjusted).toHaveBeenCalledOnce());
    expect(onAdjusted.mock.calls[0][0].balance).toBe(2500);
  });

  it("flips the wire amount to negative when the direction is debit", async () => {
    let capturedBody: AdjustBody | null = null;
    server.use(
      http.post(ADJUST_PATH, async ({ request }) => {
        capturedBody = (await request.json()) as AdjustBody;
        return HttpResponse.json({
          balance: capturedBody.amount,
          reserved: 0,
          available: capturedBody.amount,
          leaderTermsAcceptedAt: null,
          recentLedger: [],
        });
      }),
    );
    const onAdjusted = vi.fn();
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={onAdjusted} />,
    );

    await userEvent.click(
      screen.getByTestId("admin-wallet-adjust-direction-debit"),
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "1000",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Recovering overpaid commission",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() => expect(onAdjusted).toHaveBeenCalledOnce());
    expect(capturedBody).not.toBeNull();
    expect(capturedBody!.amount).toBe(-1000);
    expect(capturedBody!.reason).toBe("Recovering overpaid commission");
    expect(onAdjusted.mock.calls[0][0].balance).toBe(-1000);
  });

  it("disables submit when reason is blank", async () => {
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "100",
    );
    expect(screen.getByTestId("admin-wallet-adjust-submit")).toBeDisabled();
  });

  it("disables submit when amount is missing or non-positive", async () => {
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Just a reason",
    );
    expect(screen.getByTestId("admin-wallet-adjust-submit")).toBeDisabled();

    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "0",
    );
    expect(screen.getByTestId("admin-wallet-adjust-submit")).toBeDisabled();
  });

  it("clears the inputs after a successful adjustment", async () => {
    server.use(echoHandler());
    const onAdjusted = vi.fn();
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={onAdjusted} />,
    );

    const amount = screen.getByTestId(
      "admin-wallet-adjust-amount",
    ) as HTMLInputElement;
    const reason = screen.getByTestId(
      "admin-wallet-adjust-reason",
    ) as HTMLTextAreaElement;

    await userEvent.type(amount, "300");
    await userEvent.type(reason, "Reimburse listing fee");
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() => expect(onAdjusted).toHaveBeenCalledOnce());
    expect(amount.value).toBe("");
    expect(reason.value).toBe("");
  });

  it("updates the reason character counter as the user types", async () => {
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "abcde",
    );
    expect(
      screen.getByTestId("admin-wallet-adjust-reason-counter").textContent,
    ).toContain("5 / 500");
  });

  it("surfaces INSUFFICIENT_GROUP_BALANCE with code-specific copy", async () => {
    server.use(
      http.post(ADJUST_PATH, () =>
        HttpResponse.json(
          {
            status: 422,
            code: "INSUFFICIENT_GROUP_BALANCE",
            title: "Insufficient group balance",
          },
          { status: 422 },
        ),
      ),
    );
    const onAdjusted = vi.fn();
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={onAdjusted} />,
    );

    await userEvent.click(
      screen.getByTestId("admin-wallet-adjust-direction-debit"),
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "9000",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Over-debit attempt",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() =>
      expect(
        screen.getByTestId("admin-wallet-adjust-error"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByText(/Debit would push balance below zero/i),
    ).toBeInTheDocument();
    expect(onAdjusted).not.toHaveBeenCalled();
  });

  it("surfaces ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE with code-specific copy", async () => {
    server.use(
      http.post(ADJUST_PATH, () =>
        HttpResponse.json(
          {
            status: 422,
            code: "ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE",
            title: "Amount out of range",
            ceiling: 10000000,
          },
          { status: 422 },
        ),
      ),
    );
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );

    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "99999999",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Way too big",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/exceeds the configured sanity ceiling/i),
      ).toBeInTheDocument(),
    );
  });

  it("surfaces REALTY_GROUP_NOT_FOUND with code-specific copy", async () => {
    server.use(
      http.post(ADJUST_PATH, () =>
        HttpResponse.json(
          {
            status: 404,
            code: "REALTY_GROUP_NOT_FOUND",
            title: "Realty group not found",
          },
          { status: 404 },
        ),
      ),
    );
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );

    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "100",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Dead group",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/group no longer exists/i),
      ).toBeInTheDocument(),
    );
  });

  it("falls back to problem-details detail for non-coded errors (e.g. 403)", async () => {
    server.use(
      http.post(ADJUST_PATH, () =>
        HttpResponse.json(
          {
            status: 403,
            title: "Forbidden",
            detail: "Admin role required.",
          },
          { status: 403 },
        ),
      ),
    );
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );

    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "100",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Trying as non-admin",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/Admin role required\./i),
      ).toBeInTheDocument(),
    );
  });

  it("surfaces a 400 validation problem-details detail verbatim", async () => {
    server.use(
      http.post(ADJUST_PATH, () =>
        HttpResponse.json(
          {
            status: 400,
            title: "Validation failed",
            detail: "reason: must not be blank",
            errors: { reason: "must not be blank" },
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(
      <AdminWalletAdjustCard publicId={GROUP_ID} onAdjusted={vi.fn()} />,
    );

    // Sneak past the client-side gate with a reason that has trailing space
    // so the trimmed reason is non-empty but we can still observe the
    // backend's verbatim error rendering.
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "50",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "x",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/reason: must not be blank/i),
      ).toBeInTheDocument(),
    );
  });
});
