import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { mockUser } from "@/test/msw/fixtures";
import type { RealtyGroupPublicDto } from "@/types/realty";
import { AgentCommissionPreview } from "./AgentCommissionPreview";

const CALLER_ID = "00000000-0000-0000-0000-000000000a01";
const GROUP_ID = "00000000-0000-0000-0000-000000000a02";

function group(
  callerCommissionRate: number | null,
): RealtyGroupPublicDto {
  return {
    publicId: GROUP_ID,
    name: "Sunset Realty",
    slug: "sunset",
    description: null,
    website: null,
    logoUrl: null,
    coverUrl: null,
    memberSince: "2026-01-01T00:00:00Z",
    leader: {
      userPublicId: "00000000-0000-0000-0000-000000000b00",
      displayName: "Leader Liz",
      avatarUrl: null,
    },
    agents: [
      {
        memberPublicId: "00000000-0000-0000-0000-000000000c01",
        userPublicId: CALLER_ID,
        displayName: "Agent Alice",
        avatarUrl: null,
        role: "AGENT",
        permissions: ["CREATE_LISTING"],
        joinedAt: "2026-02-01T00:00:00Z",
        agentCommissionRate: callerCommissionRate,
      },
    ],
    memberSeatLimit: 25,
    memberCount: 2,
  };
}

function installGroup(g: RealtyGroupPublicDto) {
  server.use(
    http.get(`*/api/v1/realty-groups/${g.publicId}`, () => HttpResponse.json(g)),
  );
}

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
    installGroup(group(0.1));
    installWallet(1000);

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
      />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: CALLER_ID } },
    );

    const preview = await screen.findByTestId("agent-commission-preview");
    // Compact assertion against the rendered text — normalize whitespace
    // for robustness across the JSX line breaks.
    await waitFor(() => {
      const text = preview.textContent?.replace(/\s+/g, " ") ?? "";
      expect(text).toContain("L$95");
      expect(text).toContain("your 10% commission");
      expect(text).toContain("Sunset Realty earns L$855");
    });
  });

  it("treats a missing agent commission rate as 0 (group keeps all earnings)", async () => {
    // Caller is on the group but rate is null. agent slice = 0, group = 950.
    installGroup(group(null));
    installWallet(1000);

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
      />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: CALLER_ID } },
    );

    const preview = await screen.findByTestId("agent-commission-preview");
    await waitFor(() => {
      const text = preview.textContent?.replace(/\s+/g, " ") ?? "";
      // Rate 0 falls under the < 0.01 branch → "0.00%".
      expect(text).toContain("your 0.00% commission");
      // L$1000 - 50 platform = 950 group slice when agent rate = 0.
      expect(text).toContain("Sunset Realty earns L$950");
    });
  });

  it("renders nothing when starting bid is zero", () => {
    installGroup(group(0.1));
    installWallet(1000);

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={0}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
      />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: CALLER_ID } },
    );

    expect(
      screen.queryByTestId("agent-commission-preview"),
    ).not.toBeInTheDocument();
  });

  it("renders a shortfall message + fires onInsufficient(true) when wallet < listing fee", async () => {
    installGroup(group(0.1));
    installWallet(10);

    const onInsufficient = vi.fn();

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        onInsufficient={onInsufficient}
      />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: CALLER_ID } },
    );

    expect(
      await screen.findByText(
        /Group wallet has L\$10; deposit L\$40 to publish/,
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(onInsufficient).toHaveBeenCalledWith(true));
  });

  it("renders the wallet-source line + fires onInsufficient(false) when balance is sufficient", async () => {
    installGroup(group(0.1));
    installWallet(500);

    const onInsufficient = vi.fn();

    renderWithProviders(
      <AgentCommissionPreview
        startingBid={1000}
        groupName="Sunset Realty"
        groupPublicId={GROUP_ID}
        onInsufficient={onInsufficient}
      />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: CALLER_ID } },
    );

    expect(
      await screen.findByText(
        /Listing fee paid from Sunset Realty wallet — current balance L\$500/,
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(onInsufficient).toHaveBeenCalledWith(false));
  });
});
