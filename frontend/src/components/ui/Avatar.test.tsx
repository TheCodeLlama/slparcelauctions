import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Avatar } from "./Avatar";

describe("Avatar", () => {
  it("renders next/image with width/height when src is provided and merges consumer className", () => {
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

  it("cacheBust forces a remount by changing the key (src stays clean)", () => {
    const { rerender } = renderWithProviders(
      <Avatar src="/avatar.png" alt="Heath" cacheBust="v1" />,
    );
    const img1 = screen.getByAltText("Heath") as HTMLImageElement;
    const src1 = img1.getAttribute("src") ?? "";
    expect(src1).not.toContain("v=");

    rerender(<Avatar src="/avatar.png" alt="Heath" cacheBust="v2" />);
    const img2 = screen.getByAltText("Heath") as HTMLImageElement;
    const src2 = img2.getAttribute("src") ?? "";
    expect(src2).not.toContain("v=");
    expect(src2).toBe(src1);
  });
});
