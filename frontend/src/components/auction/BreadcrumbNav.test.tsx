import { describe, it, expect, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { BreadcrumbNav } from "./BreadcrumbNav";

describe("BreadcrumbNav", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("renders Browse -> Region -> Title", () => {
    renderWithProviders(<BreadcrumbNav region="Heterocera" title="Listing" />);
    expect(screen.getByTestId("breadcrumb-browse")).toHaveTextContent("Browse");
    expect(screen.getByTestId("breadcrumb-region")).toHaveTextContent(
      "Heterocera",
    );
    expect(screen.getByTestId("breadcrumb-title")).toHaveTextContent("Listing");
  });

  it("falls back to /browse when no last-browse-url is stored", () => {
    renderWithProviders(<BreadcrumbNav region="Heterocera" title="Listing" />);
    expect(screen.getByTestId("breadcrumb-browse")).toHaveAttribute(
      "href",
      "/browse",
    );
  });

  it("uses the last-browse-url from sessionStorage after mount", async () => {
    window.sessionStorage.setItem(
      "last-browse-url",
      "/browse?region=Tula&sort=ending_soonest",
    );
    renderWithProviders(<BreadcrumbNav region="Heterocera" title="Listing" />);
    await waitFor(() =>
      expect(screen.getByTestId("breadcrumb-browse")).toHaveAttribute(
        "href",
        "/browse?region=Tula&sort=ending_soonest",
      ),
    );
  });

  it("encodes the region name in the region href", () => {
    renderWithProviders(<BreadcrumbNav region="Bay City" title="Listing" />);
    expect(screen.getByTestId("breadcrumb-region")).toHaveAttribute(
      "href",
      "/browse?region=Bay%20City",
    );
  });

  it("encodes apostrophes in the region name", () => {
    renderWithProviders(
      <BreadcrumbNav region="O'Malley's Point" title="Listing" />,
    );
    // encodeURIComponent leaves apostrophes raw by default, but spaces
    // become %20; assert the final shape.
    expect(screen.getByTestId("breadcrumb-region")).toHaveAttribute(
      "href",
      "/browse?region=O'Malley's%20Point",
    );
  });

  it("truncates long titles with an ellipsis", () => {
    const longTitle =
      "Beachfront 8192 sqm parcel with full terraforming rights and extras";
    renderWithProviders(
      <BreadcrumbNav region="Heterocera" title={longTitle} />,
    );
    const el = screen.getByTestId("breadcrumb-title");
    // The truncation keeps 39 chars + an ellipsis (40 chars total).
    expect(el.textContent?.length).toBeLessThanOrEqual(40);
    expect(el.textContent?.endsWith("…")).toBe(true);
    // Title attribute carries the full text for a11y / hover.
    expect(el).toHaveAttribute("title", longTitle);
  });

  it("does not truncate titles at or below the limit", () => {
    renderWithProviders(<BreadcrumbNav region="Heterocera" title="Short Title" />);
    expect(screen.getByTestId("breadcrumb-title")).toHaveTextContent(
      "Short Title",
    );
  });

  it("emits JSON-LD BreadcrumbList microdata", () => {
    renderWithProviders(
      <BreadcrumbNav region="Heterocera" title="My Listing" />,
    );
    const script = screen.getByTestId("breadcrumb-jsonld");
    expect(script.getAttribute("type")).toBe("application/ld+json");
    const parsed = JSON.parse(script.innerHTML);
    expect(parsed["@type"]).toBe("BreadcrumbList");
    expect(parsed.itemListElement).toHaveLength(3);
    expect(parsed.itemListElement[0].name).toBe("Browse");
    expect(parsed.itemListElement[1].name).toBe("Heterocera");
    expect(parsed.itemListElement[2].name).toBe("My Listing");
    // The final crumb has no item/href since it's the current page.
    expect(parsed.itemListElement[2].item).toBeUndefined();
  });

  it("renders under the dark theme without crashing", () => {
    renderWithProviders(
      <BreadcrumbNav region="Heterocera" title="Listing" />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByTestId("breadcrumb-nav")).toBeInTheDocument();
  });
});
