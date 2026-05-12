import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { AgentCardDto } from "@/types/realty";
import { RealtyGroupMemberCard } from "./RealtyGroupMemberCard";

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

describe("RealtyGroupMemberCard", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-11T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders display name and AGENT badge by default", () => {
    renderWithProviders(<RealtyGroupMemberCard member={makeAgent()} />);
    expect(screen.getByText("Pat Agent")).toBeInTheDocument();
    expect(screen.getByText("Agent")).toBeInTheDocument();
  });

  it("renders LEADER badge in success tone for the leader role", () => {
    renderWithProviders(
      <RealtyGroupMemberCard member={makeAgent({ role: "LEADER" })} />,
    );
    expect(screen.getByText("Leader")).toBeInTheDocument();
  });

  it("links the avatar and name to the user's public profile", () => {
    renderWithProviders(<RealtyGroupMemberCard member={makeAgent()} />);
    const links = screen.getAllByRole("link");
    expect(links.length).toBeGreaterThanOrEqual(2);
    for (const link of links) {
      expect(link.getAttribute("href")).toBe(
        "/users/33333333-3333-3333-3333-333333333333",
      );
    }
  });

  it("hides permission chips when permissions is null (anonymous view)", () => {
    renderWithProviders(<RealtyGroupMemberCard member={makeAgent()} />);
    expect(screen.queryByTestId("member-permissions")).not.toBeInTheDocument();
  });

  it("renders permission chips with human labels when provided", () => {
    renderWithProviders(
      <RealtyGroupMemberCard
        member={makeAgent({
          permissions: ["INVITE_AGENTS", "EDIT_GROUP_PROFILE"],
        })}
      />,
    );
    const chips = screen.getByTestId("member-permissions");
    expect(chips).toBeInTheDocument();
    expect(screen.getByText("Invite agents")).toBeInTheDocument();
    expect(screen.getByText("Edit group profile")).toBeInTheDocument();
  });

  it("hides joined line when joinedAt is null", () => {
    renderWithProviders(<RealtyGroupMemberCard member={makeAgent()} />);
    expect(screen.queryByText(/Joined/)).not.toBeInTheDocument();
  });

  it("renders 'today' for joinedAt on the same day", () => {
    renderWithProviders(
      <RealtyGroupMemberCard
        member={makeAgent({ joinedAt: "2026-05-11T01:00:00Z" })}
      />,
    );
    expect(screen.getByText(/Joined today/)).toBeInTheDocument();
  });

  it("renders 'N days ago' for recent joins", () => {
    renderWithProviders(
      <RealtyGroupMemberCard
        member={makeAgent({ joinedAt: "2026-05-09T12:00:00Z" })}
      />,
    );
    expect(screen.getByText(/Joined 2 days ago/)).toBeInTheDocument();
  });

  it("renders 'N weeks ago' for week-scale joins", () => {
    renderWithProviders(
      <RealtyGroupMemberCard
        member={makeAgent({ joinedAt: "2026-04-15T12:00:00Z" })}
      />,
    );
    expect(screen.getByText(/Joined 3 weeks ago/)).toBeInTheDocument();
  });
});
