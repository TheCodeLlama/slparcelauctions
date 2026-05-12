import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupWalletHandlers } from "@/test/msw/handlers";
import { GroupWithdrawModal } from "./GroupWithdrawModal";

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function renderModal(
  props: Partial<Parameters<typeof GroupWithdrawModal>[0]> = {},
) {
  return renderWithProviders(
    <GroupWithdrawModal
      open
      onClose={() => {}}
      publicId={GROUP_ID}
      available={10000}
      {...props}
    />,
  );
}

describe("GroupWithdrawModal", () => {
  it("renders the modal with amount input when open", () => {
    renderModal();
    expect(
      screen.getByRole("dialog", { name: /Withdraw from Group Wallet/i }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("withdraw-amount-input")).toBeInTheDocument();
  });

  it("does not render when closed", () => {
    renderModal({ open: false });
    expect(
      screen.queryByRole("dialog"),
    ).not.toBeInTheDocument();
  });

  it("shows validation error for non-positive amount", async () => {
    renderModal();
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    expect(
      screen.getByText(/Enter a positive integer amount/i),
    ).toBeInTheDocument();
  });

  it("shows validation error when amount exceeds available", async () => {
    renderModal({ available: 500 });
    await userEvent.type(
      screen.getByTestId("withdraw-amount-input"),
      "1000",
    );
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    expect(
      screen.getByText(/Available balance is L\$500/i),
    ).toBeInTheDocument();
  });

  it("submits successfully and closes on 202", async () => {
    // Default handler returns 202
    let closed = false;
    renderModal({ onClose: () => { closed = true; } });
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "500");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() => expect(closed).toBe(true));
  });

  it("shows INSUFFICIENT_GROUP_BALANCE error from API", async () => {
    server.use(realtyGroupWalletHandlers.withdrawInsufficientBalance(800, 1000));
    renderModal({ available: 2000 });
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "1000");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() =>
      expect(
        screen.getByText(/Insufficient balance/i),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText(/Available: L\$800/i)).toBeInTheDocument();
  });

  it("shows LEADER_TERMS_NOT_ACCEPTED error message", async () => {
    server.use(
      http.post(
        "*/api/v1/realty/groups/*/wallet/withdraw",
        () =>
          HttpResponse.json(
            {
              status: 422,
              code: "LEADER_TERMS_NOT_ACCEPTED",
              title: "Leader terms not accepted",
              leaderPublicId: "11111111-1111-1111-1111-111111111111",
            },
            { status: 422 },
          ),
      ),
    );
    renderModal();
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "500");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() =>
      expect(
        screen.getByText(/has not accepted the Wallet Terms of Service/i),
      ).toBeInTheDocument(),
    );
  });

  it("shows LEADER_FROZEN error message", async () => {
    server.use(
      http.post(
        "*/api/v1/realty/groups/*/wallet/withdraw",
        () =>
          HttpResponse.json(
            {
              status: 422,
              code: "LEADER_FROZEN",
              title: "Leader frozen",
            },
            { status: 422 },
          ),
      ),
    );
    renderModal();
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "500");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() =>
      expect(
        screen.getByText(/leader's account is currently restricted/i),
      ).toBeInTheDocument(),
    );
  });

  it("shows an error message for GROUP_DISSOLVED (410)", async () => {
    server.use(realtyGroupWalletHandlers.withdraw410());
    renderModal();
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "500");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    // The modal surfaces the ApiError message for non-special-cased codes.
    // The 410 handler returns title "Group is dissolved" which becomes the message.
    await waitFor(() =>
      expect(
        screen.getByText(/Group is dissolved/i),
      ).toBeInTheDocument(),
    );
  });
});
