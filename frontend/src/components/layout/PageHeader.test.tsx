import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PageHeader } from "./PageHeader";

describe("PageHeader", () => {
  it("renders the title as an h1", () => {
    renderWithProviders(<PageHeader title="Browse Auctions" />);
    expect(screen.getByRole("heading", { level: 1, name: "Browse Auctions" })).toBeInTheDocument();
  });

  it("renders the subtitle when provided", () => {
    renderWithProviders(<PageHeader title="Title" subtitle="A descriptive subtitle." />);
    expect(screen.getByText("A descriptive subtitle.")).toBeInTheDocument();
  });

  it("does not render the subtitle when absent", () => {
    renderWithProviders(<PageHeader title="Title" />);
    expect(screen.queryByText(/A descriptive subtitle/)).toBeNull();
  });

  it("renders the actions slot when provided", () => {
    renderWithProviders(
      <PageHeader title="Title" actions={<button>Create</button>} />
    );
    expect(screen.getByRole("button", { name: "Create" })).toBeInTheDocument();
  });

  it("renders breadcrumbs with separators and marks the last one aria-current", () => {
    renderWithProviders(
      <PageHeader
        title="Auction #42"
        breadcrumbs={[
          { label: "Browse", href: "/browse" },
          { label: "Auction #42" },
        ]}
      />
    );
    const browseLink = screen.getByRole("link", { name: "Browse" });
    expect(browseLink.getAttribute("href")).toBe("/browse");
    const current = screen.getByText("Auction #42", { selector: "span" });
    expect(current.getAttribute("aria-current")).toBe("page");
  });
});
