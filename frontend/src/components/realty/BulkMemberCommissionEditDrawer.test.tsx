import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  fireEvent,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type {
  AgentCardDto,
  BulkCommissionRatesRequest,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { BulkMemberCommissionEditDrawer } from "./BulkMemberCommissionEditDrawer";

const GROUP_ID = "00000000-0000-0000-0000-0000000000aa";
const LEADER_USER_ID = "11111111-1111-1111-1111-111111111111";
const AGENT_A_MEMBER_ID = "22222222-2222-2222-2222-222222222222";
const AGENT_B_MEMBER_ID = "33333333-3333-3333-3333-333333333333";

function makeAgent(overrides: Partial<AgentCardDto> = {}): AgentCardDto {
  return {
    memberPublicId: AGENT_A_MEMBER_ID,
    userPublicId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    displayName: "Alpha Agent",
    avatarUrl: null,
    role: "AGENT",
    permissions: [],
    joinedAt: "2026-04-15T10:00:00Z",
    agentCommissionRate: 0.1,
    ...overrides,
  };
}

function makeGroup(
  overrides: Partial<RealtyGroupPublicDto> = {},
): RealtyGroupPublicDto {
  return {
    publicId: GROUP_ID,
    name: "Mainland Realty",
    slug: "mainland-realty",
    description: null,
    website: null,
    logoUrl: null,
    coverUrl: null,
    memberSince: "2026-04-01T10:00:00Z",
    leader: {
      userPublicId: LEADER_USER_ID,
      displayName: "Leader Lee",
      avatarUrl: null,
    },
    agents: [
      makeAgent(),
      makeAgent({
        memberPublicId: AGENT_B_MEMBER_ID,
        userPublicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        displayName: "Bravo Agent",
        agentCommissionRate: 0.2,
      }),
    ],
    memberSeatLimit: 50,
    memberCount: 3,
    ...overrides,
  };
}

describe("BulkMemberCommissionEditDrawer", () => {
  it("happyPath_submits_with_updated_rates — PATCHes the new rates, closes, toasts", async () => {
    let captured: BulkCommissionRatesRequest | null = null;
    server.use(
      http.patch(
        `*/api/v1/realty-groups/${GROUP_ID}/members/commission-rates`,
        async ({ request }) => {
          captured = (await request.json()) as BulkCommissionRatesRequest;
          return new HttpResponse(null, { status: 204 });
        },
      ),
    );
    const onClose = vi.fn();

    renderWithProviders(
      <BulkMemberCommissionEditDrawer
        open
        group={makeGroup()}
        onClose={onClose}
      />,
      { auth: "authenticated" },
    );

    // Inputs prefilled with current percentage rates.
    const alphaInput = screen.getByTestId(
      `bulk-commission-input-${AGENT_A_MEMBER_ID}`,
    ) as HTMLInputElement;
    const bravoInput = screen.getByTestId(
      `bulk-commission-input-${AGENT_B_MEMBER_ID}`,
    ) as HTMLInputElement;
    expect(alphaInput.value).toBe("10");
    expect(bravoInput.value).toBe("20");

    await userEvent.clear(alphaInput);
    await userEvent.type(alphaInput, "15");
    await userEvent.clear(bravoInput);
    await userEvent.type(bravoInput, "25");

    await userEvent.click(screen.getByTestId("bulk-commission-submit"));

    await waitFor(() => expect(captured).not.toBeNull());
    const sent = captured as unknown as BulkCommissionRatesRequest;
    // Order is alpha-by-display-name (Alpha, Bravo).
    expect(sent.memberRates).toHaveLength(2);
    expect(sent.memberRates[0].memberPublicId).toBe(AGENT_A_MEMBER_ID);
    expect(Number(sent.memberRates[0].rate)).toBeCloseTo(0.15);
    expect(sent.memberRates[1].memberPublicId).toBe(AGENT_B_MEMBER_ID);
    expect(Number(sent.memberRates[1].rate)).toBeCloseTo(0.25);

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(await screen.findByText(/Rates updated/i)).toBeInTheDocument();
  });

  it("validationError_negativeRate_blocksSubmit — inline error, no network call", async () => {
    let networkHit = false;
    server.use(
      http.patch(
        `*/api/v1/realty-groups/${GROUP_ID}/members/commission-rates`,
        () => {
          networkHit = true;
          return new HttpResponse(null, { status: 204 });
        },
      ),
    );
    const onClose = vi.fn();

    renderWithProviders(
      <BulkMemberCommissionEditDrawer
        open
        group={makeGroup()}
        onClose={onClose}
      />,
      { auth: "authenticated" },
    );

    const alphaInput = screen.getByTestId(
      `bulk-commission-input-${AGENT_A_MEMBER_ID}`,
    ) as HTMLInputElement;
    // userEvent's keyboard simulation against type="number" inputs is
    // flaky for the "-" character on some platforms (the browser's value
    // sanitiser drops a stray sign mid-typing). Drive the change event
    // directly so the negative-rate path is deterministic.
    fireEvent.change(alphaInput, { target: { value: "-5" } });
    expect(alphaInput.value).toBe("-5");

    fireEvent.submit(
      screen.getByTestId("bulk-commission-form") as HTMLFormElement,
    );

    expect(
      await screen.findByTestId(
        `bulk-commission-error-${AGENT_A_MEMBER_ID}`,
      ),
    ).toHaveTextContent(/0 or greater/i);
    expect(networkHit).toBe(false);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("serverError_400_showsInlineError — MEMBER_NOT_IN_GROUP pins to row", async () => {
    server.use(
      http.patch(
        `*/api/v1/realty-groups/${GROUP_ID}/members/commission-rates`,
        () =>
          HttpResponse.json(
            {
              status: 400,
              code: "MEMBER_NOT_IN_GROUP",
              title: "Member not in group",
              detail: `No member with publicId ${AGENT_B_MEMBER_ID}.`,
            },
            { status: 400 },
          ),
      ),
    );
    const onClose = vi.fn();

    renderWithProviders(
      <BulkMemberCommissionEditDrawer
        open
        group={makeGroup()}
        onClose={onClose}
      />,
      { auth: "authenticated" },
    );

    await userEvent.click(screen.getByTestId("bulk-commission-submit"));

    expect(
      await screen.findByTestId(
        `bulk-commission-error-${AGENT_B_MEMBER_ID}`,
      ),
    ).toHaveTextContent(/no longer in the group/i);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("closesOnCancel — fires onClose, does not call the API", async () => {
    let networkHit = false;
    server.use(
      http.patch(
        `*/api/v1/realty-groups/${GROUP_ID}/members/commission-rates`,
        () => {
          networkHit = true;
          return new HttpResponse(null, { status: 204 });
        },
      ),
    );
    const onClose = vi.fn();

    renderWithProviders(
      <BulkMemberCommissionEditDrawer
        open
        group={makeGroup()}
        onClose={onClose}
      />,
      { auth: "authenticated" },
    );

    await userEvent.click(screen.getByTestId("bulk-commission-cancel"));

    expect(onClose).toHaveBeenCalled();
    expect(networkHit).toBe(false);
  });
});
