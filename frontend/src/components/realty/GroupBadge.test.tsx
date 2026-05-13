import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { GroupBadge } from "./GroupBadge";

describe("GroupBadge", () => {
  it("renders name + link to /groups/{slug}", () => {
    renderWithProviders(
      <GroupBadge groupSlug="mainland" groupName="Mainland Realty" />,
    );
    const link = screen.getByTestId("group-badge");
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("/groups/mainland");
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
  });

  it("renders subtitle when provided", () => {
    renderWithProviders(
      <GroupBadge
        groupSlug="mainland"
        groupName="Mainland Realty"
        subtitle="42 active listings"
      />,
    );
    expect(screen.getByText("42 active listings")).toBeInTheDocument();
  });

  it("renders logo via apiUrl when provided", () => {
    const { container } = renderWithProviders(
      <GroupBadge
        groupSlug="x"
        groupName="X"
        logoUrl="/api/v1/realty-groups/xyz/logo"
      />,
    );
    const img = container.querySelector("img") as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute("src")).toBe(
      "http://localhost:8080/api/v1/realty-groups/xyz/logo",
    );
  });

  it("renders initials when logo is null", () => {
    renderWithProviders(
      <GroupBadge groupSlug="x" groupName="Acme Brokerage" />,
    );
    expect(screen.getByText("AB")).toBeInTheDocument();
  });
});
