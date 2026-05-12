import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { AgentCardDto } from "@/types/realty";
import { RealtyGroupAgentsGrid } from "./RealtyGroupAgentsGrid";

function makeAgent(overrides: Partial<AgentCardDto> = {}): AgentCardDto {
  return {
    memberPublicId: "22222222-2222-2222-2222-222222222222",
    userPublicId: "33333333-3333-3333-3333-333333333333",
    displayName: "Pat Agent",
    avatarUrl: null,
    role: "AGENT",
    permissions: null,
    joinedAt: null,
    agentCommissionRate: null,
    ...overrides,
  };
}

describe("RealtyGroupAgentsGrid", () => {
  it("renders one card per agent in document order", () => {
    renderWithProviders(
      <RealtyGroupAgentsGrid
        agents={[
          makeAgent({
            memberPublicId: "a1",
            userPublicId: "u1",
            displayName: "First Agent",
          }),
          makeAgent({
            memberPublicId: "a2",
            userPublicId: "u2",
            displayName: "Second Agent",
          }),
        ]}
      />,
    );
    const cards = screen.getAllByTestId("realty-group-member-card");
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent("First Agent");
    expect(cards[1]).toHaveTextContent("Second Agent");
  });

  it("hides permission chips when viewerIsMember is false (default)", () => {
    renderWithProviders(
      <RealtyGroupAgentsGrid
        agents={[
          makeAgent({
            permissions: ["INVITE_AGENTS"],
            joinedAt: "2026-04-01T10:00:00Z",
          }),
        ]}
      />,
    );
    expect(screen.queryByTestId("member-permissions")).not.toBeInTheDocument();
    expect(screen.queryByText(/Joined/)).not.toBeInTheDocument();
  });

  it("shows permission chips + joined line when viewerIsMember is true", () => {
    renderWithProviders(
      <RealtyGroupAgentsGrid
        viewerIsMember
        agents={[
          makeAgent({
            permissions: ["INVITE_AGENTS"],
            joinedAt: "2026-04-01T10:00:00Z",
          }),
        ]}
      />,
    );
    expect(screen.getByTestId("member-permissions")).toBeInTheDocument();
    expect(screen.getByText("Invite agents")).toBeInTheDocument();
    expect(screen.getByText(/Joined/)).toBeInTheDocument();
  });

  it("renders an empty grid when given no agents", () => {
    renderWithProviders(<RealtyGroupAgentsGrid agents={[]} />);
    expect(
      screen.queryAllByTestId("realty-group-member-card"),
    ).toHaveLength(0);
  });
});
