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

// Sub-project G §7.3 — recipient picker. The modal exposes a binary radio
// group so the leader can route a withdrawal to either their verified avatar
// (the pre-G default) or the realty group's currently-registered SL group
// (bot-fulfilled via Self.GiveGroupMoney). When no SL group is registered the
// radio is omitted entirely; when the realty group has an active suspension
// the SL_GROUP option is rendered but disabled with a tooltip pointing the
// leader at the avatar fallback.
describe("GroupWithdrawModal recipient picker", () => {
  it("submits with recipient=AVATAR by default", async () => {
    let capturedBody: { recipient?: string } | null = null;
    server.use(
      http.post(
        "*/api/v1/realty/groups/*/wallet/withdraw",
        async ({ request }) => {
          capturedBody = (await request.json()) as { recipient?: string };
          return HttpResponse.json(
            { queueId: 1, estimatedFulfillmentSeconds: 30 },
            { status: 202 },
          );
        },
      ),
    );
    let closed = false;
    renderModal({
      onClose: () => {
        closed = true;
      },
      slGroup: { name: "Test Estate", suspended: false },
    });
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "1000");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() => expect(closed).toBe(true));
    expect(capturedBody).not.toBeNull();
    expect(capturedBody!.recipient).toBe("AVATAR");
  });

  it("submits with recipient=SL_GROUP when 'SL group' is selected", async () => {
    let capturedBody: { recipient?: string } | null = null;
    server.use(
      http.post(
        "*/api/v1/realty/groups/*/wallet/withdraw",
        async ({ request }) => {
          capturedBody = (await request.json()) as { recipient?: string };
          return HttpResponse.json(
            { queueId: 2, estimatedFulfillmentSeconds: 30 },
            { status: 202 },
          );
        },
      ),
    );
    let closed = false;
    renderModal({
      onClose: () => {
        closed = true;
      },
      slGroup: { name: "Test Estate", suspended: false },
    });
    await userEvent.click(
      screen.getByLabelText(/SL group: Test Estate/i),
    );
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "1000");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() => expect(closed).toBe(true));
    expect(capturedBody).not.toBeNull();
    expect(capturedBody!.recipient).toBe("SL_GROUP");
  });

  it("disables the SL group radio with a tooltip when suspended", () => {
    renderModal({
      slGroup: { name: "Test Estate", suspended: true },
    });
    const slRadio = screen.getByLabelText(
      /SL group: Test Estate/i,
    ) as HTMLInputElement;
    expect(slRadio.disabled).toBe(true);
    expect(
      screen.getByText(/group registration suspended/i),
    ).toBeInTheDocument();
  });

  it("omits the SL group radio entirely when no SL group is registered", () => {
    renderModal({ slGroup: null });
    expect(
      screen.queryByLabelText(/SL group/i),
    ).not.toBeInTheDocument();
  });

  it("maps SL_GROUP_NOT_REGISTERED to friendly copy", async () => {
    server.use(
      http.post(
        "*/api/v1/realty/groups/*/wallet/withdraw",
        () =>
          HttpResponse.json(
            {
              status: 422,
              code: "SL_GROUP_NOT_REGISTERED",
              title: "SL group not registered",
            },
            { status: 422 },
          ),
      ),
    );
    renderModal({ slGroup: { name: "Test Estate", suspended: false } });
    await userEvent.click(
      screen.getByLabelText(/SL group: Test Estate/i),
    );
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "500");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() =>
      expect(
        screen.getByText(/has no registered SL group/i),
      ).toBeInTheDocument(),
    );
  });

  it("maps SL_GROUP_REGISTRATION_SUSPENDED to friendly copy", async () => {
    server.use(
      http.post(
        "*/api/v1/realty/groups/*/wallet/withdraw",
        () =>
          HttpResponse.json(
            {
              status: 422,
              code: "SL_GROUP_REGISTRATION_SUSPENDED",
              title: "SL group registration suspended",
            },
            { status: 422 },
          ),
      ),
    );
    renderModal({ slGroup: { name: "Test Estate", suspended: false } });
    await userEvent.click(
      screen.getByLabelText(/SL group: Test Estate/i),
    );
    await userEvent.type(screen.getByTestId("withdraw-amount-input"), "500");
    await userEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() =>
      expect(
        screen.getByText(/SL-group withdrawals are blocked/i),
      ).toBeInTheDocument(),
    );
  });
});
