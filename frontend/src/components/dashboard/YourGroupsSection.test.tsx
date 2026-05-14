import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { renderWithProviders, screen } from "@/test/render";
import { YourGroupsSection } from "./YourGroupsSection";

describe("YourGroupsSection", () => {
  it("renders a card per group with role chip + quick action links", () => {
    const groups = [
      { publicId: "g1", slug: "sunset", name: "Sunset", role: "LEADER" as const },
      { publicId: "g2", slug: "moonlit", name: "Moonlit", role: "AGENT" as const },
    ];
    renderWithProviders(<YourGroupsSection groups={groups} />);

    expect(screen.getAllByTestId("your-groups-card")).toHaveLength(2);
    expect(screen.getByRole("link", { name: /Profile.*Sunset/i })).toHaveAttribute(
      "href",
      "/groups/sunset/manage/profile",
    );
    expect(screen.getByRole("link", { name: /Wallet.*Sunset/i })).toHaveAttribute(
      "href",
      "/groups/sunset/manage/wallet",
    );
    expect(screen.getByRole("link", { name: /Members.*Moonlit/i })).toHaveAttribute(
      "href",
      "/groups/moonlit/manage/members",
    );
    expect(screen.getByRole("link", { name: /Reviews.*Moonlit/i })).toHaveAttribute(
      "href",
      "/groups/moonlit/reviews",
    );
  });

  it("renders the role chip distinguishing leader from agent", () => {
    const groups = [
      { publicId: "g1", slug: "sunset", name: "Sunset", role: "LEADER" as const },
      { publicId: "g2", slug: "moonlit", name: "Moonlit", role: "AGENT" as const },
    ];
    renderWithProviders(<YourGroupsSection groups={groups} />);

    expect(screen.getByText("Leader")).toBeInTheDocument();
    expect(screen.getByText("Agent")).toBeInTheDocument();
  });

  it("renders nothing when groups list is empty", () => {
    const { container } = render(<YourGroupsSection groups={[]} />);
    expect(container.firstChild).toBeNull();
  });
});
