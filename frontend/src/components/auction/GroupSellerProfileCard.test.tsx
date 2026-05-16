import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, within } from "@/test/render";
import type { GroupAttribution, ListingAgent } from "@/types/auction";
import { GroupSellerProfileCard } from "./GroupSellerProfileCard";

function makeGroup(overrides: Partial<GroupAttribution> = {}): GroupAttribution {
  return {
    publicId: "00000000-0000-0000-0000-0000000000aa",
    name: "Hadron Realty",
    slug: "hadron-realty",
    logoUrl: null,
    dissolved: false,
    ...overrides,
  };
}

function makeAgent(overrides: Partial<ListingAgent> = {}): ListingAgent {
  return {
    publicId: "00000000-0000-0000-0000-0000000000bb",
    displayName: "Carol Agent",
    avatarUrl: null,
    ...overrides,
  };
}

describe("GroupSellerProfileCard", () => {
  it("renders the group name as the primary heading, linking to /groups/{slug}", () => {
    renderWithProviders(
      <GroupSellerProfileCard
        group={makeGroup()}
        agent={makeAgent()}
        averageRating={4.5}
        reviewCount={10}
        completedSales={5}
        foundedAt="2025-09-12"
      />,
    );
    const nameLink = screen.getByTestId("group-seller-profile-card-name");
    expect(nameLink).toHaveTextContent("Hadron Realty");
    expect(nameLink).toHaveAttribute("href", "/groups/hadron-realty");
  });

  it("renders 'Listed by <agent>' linking to the agent's profile", () => {
    renderWithProviders(
      <GroupSellerProfileCard
        group={makeGroup()}
        agent={makeAgent()}
        averageRating={null}
        reviewCount={0}
        completedSales={0}
      />,
    );
    const listedBy = screen.getByTestId("group-seller-profile-card-listed-by");
    expect(listedBy).toHaveTextContent(/^Listed by/);
    const agentLink = within(listedBy).getByTestId(
      "group-seller-profile-card-agent-link",
    );
    expect(agentLink).toHaveTextContent("Carol Agent");
    expect(agentLink).toHaveAttribute(
      "href",
      "/users/00000000-0000-0000-0000-0000000000bb",
    );
  });

  it("does NOT render the 'Member since' line that the individual card uses", () => {
    renderWithProviders(
      <GroupSellerProfileCard
        group={makeGroup()}
        agent={makeAgent()}
        completedSales={5}
        foundedAt="2025-09-12"
      />,
    );
    expect(screen.queryByText(/Member since/i)).not.toBeInTheDocument();
    // Group-flavored equivalent renders instead.
    expect(
      screen.getByTestId("group-seller-profile-card-founded"),
    ).toHaveTextContent(/Founded Sep 2025/);
  });

  it("renders group rating + completed-sales count (not the agent's stats)", () => {
    renderWithProviders(
      <GroupSellerProfileCard
        group={makeGroup()}
        agent={makeAgent()}
        averageRating={4.2}
        reviewCount={7}
        completedSales={12}
      />,
    );
    expect(screen.getByText("4.2")).toBeInTheDocument();
    expect(screen.getByText("(7 reviews)")).toBeInTheDocument();
    expect(screen.getByText("12 completed sales")).toBeInTheDocument();
  });

  it("'View group profile' link points at /groups/{slug}", () => {
    renderWithProviders(
      <GroupSellerProfileCard
        group={makeGroup({ slug: "hadron realty" })}
        agent={makeAgent()}
        completedSales={5}
      />,
    );
    const link = screen.getByTestId("group-seller-profile-card-link");
    // Slug is URL-encoded so a space-containing slug still produces a
    // well-formed path.
    expect(link).toHaveAttribute("href", "/groups/hadron%20realty");
    expect(link).toHaveTextContent(/View group profile/i);
  });

  it("renders the New Group badge when no reviews + completed sales >= 3", () => {
    renderWithProviders(
      <GroupSellerProfileCard
        group={makeGroup()}
        agent={makeAgent()}
        averageRating={null}
        reviewCount={0}
        completedSales={5}
      />,
    );
    expect(screen.getByText(/New Group/i)).toBeInTheDocument();
  });
});
