import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { AgentCommissionPreview } from "./AgentCommissionPreview";

const GROUP_ID = "00000000-0000-0000-0000-000000000a02";

function installWallet(available: number) {
  server.use(
    http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
      HttpResponse.json({
        balance: available,
        reserved: 0,
        available,
        recentLedger: [],
      }),
    ),
  );
}

describe("AgentCommissionPreview (case 3)", () => {
  it("computes the agent/group split per spec §6.3 (floor rounding)", async () => {
    // L$1000 starting → platform commission floor(1000 * 0.05) = 50.
    //   earnings = 950
    //   agent (10% of 950) = floor(95) = 95
    //   group = 950 - 95 = 855
    installWallet(1000);

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        agentCommissionRate={0.1}
      />,
    );

    const preview = await screen.findByTestId("agent-commission-preview");
    await waitFor(() => {
      // dt/dd siblings have no inline whitespace between them, so the
      // visible "<label> <value>" is "labelvalue" in textContent. Assert
      // on each row's components separately to stay robust against
      // future layout tweaks.
      const text = preview.textContent ?? "";
      expect(text).toContain("List price");
      expect(text).toContain("L$1,000");
      expect(text).toContain("Platform commission at list price");
      expect(text).toContain("L$50");
      expect(text).toContain("(5%)");
      expect(text).toContain("Your earnings at list price");
      expect(text).toContain("L$95");
      expect(text).toContain("(10% of remaining)");
      expect(text).toContain("Sunset Realty earnings at list price");
      expect(text).toContain("L$855");
      expect(text).toContain("(remaining)");
    });
  });

  it("treats a zero agent commission rate as 0 (group keeps all earnings)", async () => {
    // Caller has a rate of 0 (defensive default when no member row).
    // agent slice = 0, group = 950.
    installWallet(1000);

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        agentCommissionRate={0}
      />,
    );

    const preview = await screen.findByTestId("agent-commission-preview");
    await waitFor(() => {
      const text = preview.textContent ?? "";
      // Rate 0 falls under the < 0.01 branch → "0.00%".
      expect(text).toContain("Your earnings at list price");
      expect(text).toContain("L$0");
      expect(text).toContain("(0.00% of remaining)");
      // L$1000 - 50 platform = 950 group slice when agent rate = 0.
      expect(text).toContain("Sunset Realty earnings at list price");
      expect(text).toContain("L$950");
    });
  });

  it("renders nothing when starting bid is zero", () => {
    installWallet(1000);

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={0}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        agentCommissionRate={0.1}
      />,
    );

    expect(
      screen.queryByTestId("agent-commission-preview"),
    ).not.toBeInTheDocument();
  });

  it("renders a shortfall message + fires onInsufficient(true) when wallet < listing fee", async () => {
    installWallet(10);

    const onInsufficient = vi.fn();

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        agentCommissionRate={0.1}
        onInsufficient={onInsufficient}
      />,
    );

    expect(
      await screen.findByText(
        /Group wallet has L\$10; deposit L\$40 to publish/,
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(onInsufficient).toHaveBeenCalledWith(true));
  });

  it("renders the wallet-source line + fires onInsufficient(false) when balance is sufficient", async () => {
    installWallet(500);

    const onInsufficient = vi.fn();

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        agentCommissionRate={0.1}
        onInsufficient={onInsufficient}
      />,
    );

    expect(
      await screen.findByText(
        /Listing fee paid from Sunset Realty wallet — current balance L\$500/,
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(onInsufficient).toHaveBeenCalledWith(false));
  });
});
