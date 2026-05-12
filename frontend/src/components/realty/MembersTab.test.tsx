import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import type {
  AgentCardDto,
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { MembersTab } from "./MembersTab";

const LEADER_USER_ID = "11111111-1111-1111-1111-111111111111";
const AGENT_USER_ID = "44444444-4444-4444-4444-444444444444";

function makeAgent(overrides: Partial<AgentCardDto> = {}): AgentCardDto {
  return {
    memberPublicId: "33333333-3333-3333-3333-333333333333",
    userPublicId: AGENT_USER_ID,
    displayName: "Agent Alpha",
    avatarUrl: null,
    role: "AGENT",
    permissions: ["INVITE_AGENTS"],
    joinedAt: "2026-04-15T10:00:00Z",
    agentCommissionRate: null,
    ...overrides,
  };
}

function makeGroup(
  overrides: Partial<RealtyGroupPublicDto> = {},
): RealtyGroupPublicDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
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
    agents: [makeAgent()],
    memberSeatLimit: 50,
    memberCount: 2,
    ...overrides,
  };
}

function permSet(...flags: RealtyGroupPermission[]) {
  return new Set(flags);
}

describe("MembersTab", () => {
  it("renders leader + agents in alphabetical order", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup({
          agents: [
            makeAgent({ displayName: "Charlie", userPublicId: "u-c" }),
            makeAgent({ displayName: "Alice", userPublicId: "u-a" }),
          ],
        })}
        callerPermissions={permSet()}
        isLeader={true}
        callerUserPublicId={LEADER_USER_ID}
      />,
    );
    const rows = screen.getAllByTestId(/^member-row-/);
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain("Alice");
    expect(rows[1].textContent).toContain("Charlie");
    expect(rows[2].textContent).toContain("Leader Lee");
  });

  it("does not show a Remove button for the leader row", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
        callerUserPublicId={LEADER_USER_ID}
      />,
    );
    expect(
      screen.queryByTestId(`member-remove-${LEADER_USER_ID}`),
    ).not.toBeInTheDocument();
  });

  it("shows the Remove button on agent rows when caller has REMOVE_AGENTS", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet("REMOVE_AGENTS")}
        isLeader={false}
        callerUserPublicId="00000000-0000-0000-0000-000000000099"
      />,
    );
    expect(
      screen.getByTestId(`member-remove-${AGENT_USER_ID}`),
    ).toBeInTheDocument();
  });

  it("hides the Remove button when caller lacks REMOVE_AGENTS and is not leader", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={false}
        callerUserPublicId="00000000-0000-0000-0000-000000000099"
      />,
    );
    expect(
      screen.queryByTestId(`member-remove-${AGENT_USER_ID}`),
    ).not.toBeInTheDocument();
  });

  it("only shows Edit permissions for the leader caller", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet("REMOVE_AGENTS")}
        isLeader={false}
        callerUserPublicId="00000000-0000-0000-0000-000000000099"
      />,
    );
    expect(
      screen.queryByTestId(`member-edit-permissions-${AGENT_USER_ID}`),
    ).not.toBeInTheDocument();
  });

  it("shows Edit permissions when caller is leader", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
        callerUserPublicId={LEADER_USER_ID}
      />,
    );
    expect(
      screen.getByTestId(`member-edit-permissions-${AGENT_USER_ID}`),
    ).toBeInTheDocument();
  });

  it("shows Leave group on the caller's own non-leader row", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={false}
        callerUserPublicId={AGENT_USER_ID}
      />,
    );
    expect(screen.getByTestId("member-leave-self")).toBeInTheDocument();
  });

  it("does not show Leave group on the leader's own row", () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
        callerUserPublicId={LEADER_USER_ID}
      />,
    );
    expect(screen.queryByTestId("member-leave-self")).not.toBeInTheDocument();
  });

  it("opens the confirm-remove modal when Remove is clicked", async () => {
    renderWithProviders(
      <MembersTab
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
        callerUserPublicId={LEADER_USER_ID}
      />,
    );
    await userEvent.click(
      screen.getByTestId(`member-remove-${AGENT_USER_ID}`),
    );
    expect(screen.getByTestId("member-remove-confirm")).toBeInTheDocument();
    expect(
      screen.getByText(/They will lose access to the group/i),
    ).toBeInTheDocument();
  });
});
