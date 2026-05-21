import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { RealtyGroupHeroBanner } from "./RealtyGroupHeroBanner";

function baseProps(): React.ComponentProps<typeof RealtyGroupHeroBanner> {
  return {
    name: "Mainland Realty",
    slug: "mainland-realty",
    description: null,
    website: null,
    memberSince: "2026-04-01T10:00:00Z",
    memberCount: 1,
    coverLightUrl: null,
    coverDarkUrl: null,
    logoLightUrl: null,
    logoDarkUrl: null,
  };
}

describe("RealtyGroupHeroBanner", () => {
  it("renders the name as an h1 and the member-count chip", () => {
    renderWithProviders(<RealtyGroupHeroBanner {...baseProps()} />);
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent("Mainland Realty");
    expect(screen.getByTestId("member-count-chip")).toHaveTextContent("1 member");
  });

  it("renders the cover image through apiUrl when set", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        coverLightUrl="/api/v1/realty-groups/abc/cover"
      />,
    );
    const cover = screen.getByTestId("realty-group-hero-cover") as HTMLImageElement;
    expect(cover.getAttribute("src")).toBe(
      "http://localhost:8080/api/v1/realty-groups/abc/cover",
    );
    expect(screen.queryByTestId("realty-group-hero-cover-empty")).not.toBeInTheDocument();
  });

  it("renders the dark cover variant when the theme is dark", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        coverLightUrl="/api/v1/realty-groups/abc/cover?variant=light"
        coverDarkUrl="/api/v1/realty-groups/abc/cover?variant=dark"
      />,
      { theme: "dark", forceTheme: true },
    );
    const cover = screen.getByTestId("realty-group-hero-cover") as HTMLImageElement;
    expect(cover.getAttribute("src")).toBe(
      "http://localhost:8080/api/v1/realty-groups/abc/cover?variant=dark",
    );
  });

  it("renders a gradient empty state when both cover variants are null", () => {
    renderWithProviders(<RealtyGroupHeroBanner {...baseProps()} />);
    expect(
      screen.getByTestId("realty-group-hero-cover-empty"),
    ).toBeInTheDocument();
  });

  it("renders the logo through apiUrl when set", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        logoLightUrl="/api/v1/realty-groups/abc/logo"
      />,
    );
    const logo = screen.getByTestId("realty-group-hero-logo") as HTMLImageElement;
    expect(logo.getAttribute("src")).toBe(
      "http://localhost:8080/api/v1/realty-groups/abc/logo",
    );
  });

  it("renders initials fallback when logo is null", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        name="Acme Brokerage"
      />,
    );
    const fallback = screen.getByTestId("realty-group-hero-logo-empty");
    expect(fallback).toHaveTextContent("AB");
  });

  it("pluralizes member count correctly", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner {...baseProps()} memberCount={5} />,
    );
    expect(screen.getByTestId("member-count-chip")).toHaveTextContent(
      "5 members",
    );
  });

  it("renders the description when provided", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        description="Premium Mainland brokerage."
      />,
    );
    expect(
      screen.getByText("Premium Mainland brokerage."),
    ).toBeInTheDocument();
  });

  it("renders the member-since line in 'Month Year' format", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        memberSince="2026-04-01T10:00:00Z"
      />,
    );
    expect(screen.getByText(/Member since April 2026/)).toBeInTheDocument();
  });

  it("renders the website link with the spec-required rel attributes", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        website="https://example.com"
      />,
    );
    const link = screen.getByTestId("realty-group-hero-website");
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("https://example.com");
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener nofollow ugc");
  });

  it("omits the website link when website is null", () => {
    renderWithProviders(<RealtyGroupHeroBanner {...baseProps()} />);
    expect(
      screen.queryByTestId("realty-group-hero-website"),
    ).not.toBeInTheDocument();
  });

  it("renders the edit affordance slot when provided", () => {
    renderWithProviders(
      <RealtyGroupHeroBanner
        {...baseProps()}
        editAffordance={
          <button data-testid="edit-slot" type="button">
            Edit
          </button>
        }
      />,
    );
    expect(screen.getByTestId("edit-slot")).toBeInTheDocument();
  });
});
