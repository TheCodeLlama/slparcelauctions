import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { Lightbox, type LightboxImage } from "./Lightbox";

const IMAGES: LightboxImage[] = [
  { id: 1, url: "https://cdn.example/1.jpg" },
  { id: 2, url: "https://cdn.example/2.jpg" },
  { id: 3, url: "https://cdn.example/3.jpg" },
];

function Harness({
  initial = 0,
  onClose,
  images = IMAGES,
}: {
  initial?: number | null;
  onClose?: () => void;
  images?: LightboxImage[];
}) {
  const [index, setIndex] = useState<number | null>(initial);
  return (
    <Lightbox
      images={images}
      openIndex={index}
      onClose={() => {
        onClose?.();
        setIndex(null);
      }}
      onIndexChange={setIndex}
    />
  );
}

describe("Lightbox", () => {
  it("does not render panel content when closed", () => {
    renderWithProviders(<Harness initial={null} />);
    expect(screen.queryByTestId("lightbox")).toBeNull();
  });

  it("renders image and counter when open", () => {
    renderWithProviders(<Harness initial={0} />);
    expect(screen.getByTestId("lightbox")).toBeInTheDocument();
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("1 / 3");
    expect(screen.getByTestId("lightbox-image").getAttribute("src")).toBe(
      "https://cdn.example/1.jpg",
    );
  });

  it("ArrowRight advances the index and wraps past the last image", async () => {
    renderWithProviders(<Harness initial={0} />);
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("2 / 3");
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("3 / 3");
    // Wrap.
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("1 / 3");
  });

  it("ArrowLeft moves back and wraps from the first image to the last", async () => {
    renderWithProviders(<Harness initial={0} />);
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("3 / 3");
  });

  it("Home jumps to the first image, End jumps to the last", async () => {
    renderWithProviders(<Harness initial={1} />);
    await userEvent.keyboard("{End}");
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("3 / 3");
    await userEvent.keyboard("{Home}");
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("1 / 3");
  });

  it("Escape closes the dialog", async () => {
    const onClose = vi.fn();
    renderWithProviders(<Harness initial={0} onClose={onClose} />);
    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("close button fires onClose", async () => {
    const onClose = vi.fn();
    renderWithProviders(<Harness initial={0} onClose={onClose} />);
    await userEvent.click(screen.getByTestId("lightbox-close"));
    expect(onClose).toHaveBeenCalled();
  });

  it("thumbnail click jumps to that image", async () => {
    renderWithProviders(<Harness initial={0} />);
    await userEvent.click(screen.getByTestId("lightbox-thumb-2"));
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("3 / 3");
    expect(screen.getByTestId("lightbox-image").getAttribute("src")).toBe(
      "https://cdn.example/3.jpg",
    );
  });

  it("marks the active thumbnail with aria-current", () => {
    renderWithProviders(<Harness initial={1} />);
    expect(screen.getByTestId("lightbox-thumb-1")).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(screen.getByTestId("lightbox-thumb-0")).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("prev / next buttons are visible when multiple images exist", async () => {
    renderWithProviders(<Harness initial={0} />);
    await userEvent.click(screen.getByTestId("lightbox-next"));
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("2 / 3");
    await userEvent.click(screen.getByTestId("lightbox-prev"));
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("1 / 3");
  });

  it("omits prev/next/strip for single-image galleries", () => {
    renderWithProviders(<Harness initial={0} images={[IMAGES[0]]} />);
    expect(screen.queryByTestId("lightbox-prev")).toBeNull();
    expect(screen.queryByTestId("lightbox-next")).toBeNull();
    expect(screen.queryByTestId("lightbox-strip")).toBeNull();
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("1 / 1");
  });

  it("renders under the dark theme without crashing", () => {
    renderWithProviders(<Harness initial={0} />, { theme: "dark", forceTheme: true });
    expect(screen.getByTestId("lightbox")).toBeInTheDocument();
  });
});
