import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { LeaderCardDto } from "@/types/realty";
import { LeaderCard } from "./LeaderCard";

function makeLeader(overrides: Partial<LeaderCardDto> = {}): LeaderCardDto {
  return {
    userPublicId: "11111111-1111-1111-1111-111111111111",
    displayName: "Avery Leader",
    avatarUrl: null,
    ...overrides,
  };
}

describe("LeaderCard", () => {
  it("renders display name and a Leader badge", () => {
    renderWithProviders(<LeaderCard leader={makeLeader()} />);
    expect(screen.getByText("Avery Leader")).toBeInTheDocument();
    expect(screen.getByText("Leader")).toBeInTheDocument();
  });

  it("links to the user's public profile page", () => {
    renderWithProviders(<LeaderCard leader={makeLeader()} />);
    const links = screen.getAllByRole("link");
    expect(links[0].getAttribute("href")).toBe(
      "/users/11111111-1111-1111-1111-111111111111",
    );
  });

  it("renders an avatar image when avatarUrl is set", () => {
    renderWithProviders(
      <LeaderCard
        leader={makeLeader({
          avatarUrl: "/api/v1/users/42/avatar/256",
        })}
      />,
    );
    const img = screen.getByAltText("Avery Leader") as HTMLImageElement;
    expect(img.tagName).toBe("IMG");
    expect(img.getAttribute("src")).toContain(
      "/api/v1/users/42/avatar/256",
    );
  });

  it("renders initials when avatarUrl is null", () => {
    renderWithProviders(<LeaderCard leader={makeLeader()} />);
    expect(screen.getByText("AL")).toBeInTheDocument();
  });
});
