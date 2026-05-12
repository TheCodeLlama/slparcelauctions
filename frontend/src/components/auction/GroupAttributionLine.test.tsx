import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { GroupAttributionLine } from "./GroupAttributionLine";

describe("GroupAttributionLine", () => {
  it("renders 'Sold by Group' heading + 'Listed by X of Group' subline with link to /group/{slug}", () => {
    render(
      <GroupAttributionLine
        agent={{ publicId: "u1", displayName: "Alice", avatarUrl: null }}
        group={{ publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: false }}
      />,
    );
    // Case-3 "Sold by" heading from Realty Groups: E §6.4.
    expect(screen.getByTestId("group-attribution-sold-by")).toHaveTextContent(
      /Sold by/i,
    );
    expect(screen.getByText(/Listed by/i)).toBeInTheDocument();
    expect(screen.getByText("Alice")).toBeInTheDocument();
    // The group name appears in both the heading link and the subline copy,
    // so query by role with the explicit slug-derived href to pin it down.
    const groupLinks = screen.getAllByRole("link", { name: /Sunset Realty/i });
    expect(groupLinks.length).toBeGreaterThanOrEqual(1);
    expect(groupLinks[0]).toHaveAttribute("href", "/group/sunset");
    // Listing-agent display name is also linked to /users/{publicId}.
    expect(screen.getByRole("link", { name: /Alice/i })).toHaveAttribute(
      "href",
      "/users/u1",
    );
  });

  it("renders only the agent-of-group line when no group is present (individual listing)", () => {
    // Individual listings have no group attribution at all — the component
    // should still return null per the dissolved-or-missing-group gate.
    const { container } = render(
      <GroupAttributionLine
        agent={{ publicId: "u1", displayName: "Alice", avatarUrl: null }}
        group={null}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when group is dissolved", () => {
    const { container } = render(
      <GroupAttributionLine
        agent={{ publicId: "u1", displayName: "Alice", avatarUrl: null }}
        group={{ publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: true }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when group is null", () => {
    const { container } = render(
      <GroupAttributionLine agent={{ publicId: "u1", displayName: "Alice", avatarUrl: null }} group={null} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when agent is null", () => {
    const { container } = render(
      <GroupAttributionLine
        agent={null}
        group={{ publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: false }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when both agent and group are null", () => {
    const { container } = render(<GroupAttributionLine agent={null} group={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("wraps avatar and logo URLs through apiUrl", () => {
    const { container } = render(
      <GroupAttributionLine
        agent={{ publicId: "u1", displayName: "Bob", avatarUrl: "/api/v1/users/u1/avatar" }}
        group={{
          publicId: "g1",
          name: "Sunset Realty",
          slug: "sunset",
          logoUrl: "/api/v1/realty-groups/g1/logo",
          dissolved: false,
        }}
      />,
    );
    // aria-hidden images are presentational; query by tag directly.
    const imgs = container.querySelectorAll("img");
    expect(imgs.length).toBeGreaterThanOrEqual(2);
    // apiUrl() prepends the API origin so the browser hits the backend.
    const srcs = Array.from(imgs).map((img) => img.getAttribute("src") ?? "");
    expect(srcs.some((s) => s.includes("/api/v1/users/u1/avatar"))).toBe(true);
    expect(srcs.some((s) => s.includes("/api/v1/realty-groups/g1/logo"))).toBe(true);
  });
});
