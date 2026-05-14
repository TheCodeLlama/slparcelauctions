import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import type {
  AgentCardDto,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { SettingsTab } from "./SettingsTab";

function makeAgent(overrides: Partial<AgentCardDto> = {}): AgentCardDto {
  return {
    memberPublicId: "33333333-3333-3333-3333-333333333333",
    userPublicId: "44444444-4444-4444-4444-444444444444",
    displayName: "Agent",
    avatarUrl: null,
    role: "AGENT",
    permissions: [],
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
      userPublicId: "11111111-1111-1111-1111-111111111111",
      displayName: "Leader",
      avatarUrl: null,
    },
    agents: [makeAgent()],
    memberSeatLimit: 50,
    memberCount: 2,
    ...overrides,
  };
}

describe("SettingsTab", () => {
  it("renders transfer and dissolve cards", () => {
    renderWithProviders(<SettingsTab group={makeGroup()} />);
    expect(screen.getByTestId("settings-transfer-button")).toBeInTheDocument();
    expect(screen.getByTestId("settings-dissolve-button")).toBeInTheDocument();
  });

  it("disables transfer button when there are no agents", () => {
    renderWithProviders(<SettingsTab group={makeGroup({ agents: [] })} />);
    expect(screen.getByTestId("settings-transfer-button")).toBeDisabled();
  });

  it("opens the transfer modal with a candidate dropdown", async () => {
    renderWithProviders(<SettingsTab group={makeGroup()} />);
    await userEvent.click(screen.getByTestId("settings-transfer-button"));
    const select = screen.getByTestId("settings-transfer-select");
    expect(select).toBeInTheDocument();
    expect(select.textContent).toContain("Agent");
  });

  it("encodes the candidate option value as memberPublicId, not userPublicId", async () => {
    // The backend's transferLeadership service does
    // `members.findByPublicId(newLeaderPublicId)` -- it expects the
    // realty_group_members row UUID, not the user UUID. Sending a
    // userPublicId here triggers a 400 TRANSFER_TARGET_NOT_MEMBER. This
    // test locks the wire shape so we can't regress.
    renderWithProviders(
      <SettingsTab
        group={makeGroup({
          agents: [
            makeAgent({
              memberPublicId: "mmmmmmmm-mmmm-mmmm-mmmm-mmmmmmmmmmmm",
              userPublicId: "uuuuuuuu-uuuu-uuuu-uuuu-uuuuuuuuuuuu",
              displayName: "Agent Zero",
            }),
          ],
        })}
      />,
    );
    await userEvent.click(screen.getByTestId("settings-transfer-button"));
    const select = screen.getByTestId(
      "settings-transfer-select",
    ) as HTMLSelectElement;
    const agentOption = Array.from(select.options).find(
      (o) => o.textContent === "Agent Zero",
    );
    expect(agentOption).toBeDefined();
    expect(agentOption!.value).toBe("mmmmmmmm-mmmm-mmmm-mmmm-mmmmmmmmmmmm");
    expect(agentOption!.value).not.toBe("uuuuuuuu-uuuu-uuuu-uuuu-uuuuuuuuuuuu");
  });

  it("disables the dissolve confirm until the group name is typed", async () => {
    renderWithProviders(<SettingsTab group={makeGroup({ name: "Tricky" })} />);
    await userEvent.click(screen.getByTestId("settings-dissolve-button"));
    const confirm = screen.getByTestId("settings-dissolve-confirm");
    expect(confirm).toBeDisabled();
    await userEvent.type(
      screen.getByTestId("settings-dissolve-confirm-input"),
      "Tricky",
    );
    expect(confirm).not.toBeDisabled();
  });
});
