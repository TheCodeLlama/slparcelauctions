import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { GroupChip } from "./GroupChip";

describe("GroupChip", () => {
  it("links to the slug-derived group URL and renders the name", () => {
    renderWithProviders(
      <GroupChip groupSlug="mainland-realty" groupName="Mainland Realty" />,
    );
    const link = screen.getByTestId("group-chip");
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("/groups/mainland-realty");
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
  });

  it("renders the logo through apiUrl when provided", () => {
    const { container } = renderWithProviders(
      <GroupChip
        groupSlug="x"
        groupName="X Realty"
        logoUrl="/api/v1/realty-groups/abc/logo"
      />,
    );
    const img = container.querySelector("img") as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute("src")).toBe(
      "http://localhost:8080/api/v1/realty-groups/abc/logo",
    );
  });

  it("renders initials fallback when logo is missing", () => {
    renderWithProviders(
      <GroupChip groupSlug="x" groupName="Heath Holdings" />,
    );
    expect(screen.getByText("HH")).toBeInTheDocument();
  });

  it("URL-encodes slugs that contain special characters", () => {
    renderWithProviders(
      <GroupChip groupSlug="café-realty" groupName="Café Realty" />,
    );
    const link = screen.getByTestId("group-chip");
    expect(link.getAttribute("href")).toContain("caf%C3%A9-realty");
  });
});
