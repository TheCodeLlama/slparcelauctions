import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Avatar } from "./Avatar";

describe("Avatar", () => {
  it("renders an img with width/height when src is provided and merges consumer className", () => {
    renderWithProviders(
      <Avatar src="/avatar.png" alt="Heath" name="Heath Barcus" size="md" className="ring-2" />
    );
    const img = screen.getByAltText("Heath") as HTMLImageElement;
    expect(img.tagName).toBe("IMG");
    expect(img.getAttribute("width")).toBe("40");
    expect(img.getAttribute("height")).toBe("40");
    expect(img.className).toContain("rounded-full");
    expect(img.className).toContain("ring-2");
  });

  it("prepends API_BASE to relative src paths", () => {
    renderWithProviders(
      <Avatar src="/api/v1/users/42/avatar/256" alt="User" />
    );
    const img = screen.getByAltText("User") as HTMLImageElement;
    expect(img.getAttribute("src")).toBe("http://localhost:8080/api/v1/users/42/avatar/256");
  });

  it("leaves absolute src paths unchanged", () => {
    renderWithProviders(
      <Avatar src="https://cdn.example.com/pic.jpg" alt="User" />
    );
    const img = screen.getByAltText("User") as HTMLImageElement;
    expect(img.getAttribute("src")).toBe("https://cdn.example.com/pic.jpg");
  });

  it("appends cacheBust as a query parameter", () => {
    renderWithProviders(
      <Avatar src="/api/v1/users/42/avatar/256" alt="User" cacheBust="2026-04-16T12:00:00Z" />
    );
    const img = screen.getByAltText("User") as HTMLImageElement;
    expect(img.getAttribute("src")).toContain("v=2026-04-16T12%3A00%3A00Z");
  });

  it("renders initials fallback when src is missing", () => {
    renderWithProviders(<Avatar alt="Heath" name="Heath Barcus" />);
    expect(screen.getByText("HB")).toBeInTheDocument();
  });

  it("renders ? when neither src nor name is provided", () => {
    renderWithProviders(<Avatar alt="Unknown" />);
    expect(screen.getByText("?")).toBeInTheDocument();
  });

  it("applies the correct dimensions for each size", () => {
    const { rerender } = renderWithProviders(<Avatar alt="x" name="X X" size="xs" />);
    expect(screen.getByText("XX").parentElement?.className).toContain("size-6");
    rerender(<Avatar alt="x" name="X X" size="xl" />);
    expect(screen.getByText("XX").parentElement?.className).toContain("size-20");
  });
});
