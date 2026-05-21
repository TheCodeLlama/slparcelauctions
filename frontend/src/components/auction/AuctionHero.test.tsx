import { describe, it, expect } from "vitest";
import {
  renderWithProviders,
  screen,
  fireEvent,
  userEvent,
} from "@/test/render";
import { act } from "@testing-library/react";
import type { AuctionPhotoDto } from "@/types/auction";
import { AuctionHero } from "./AuctionHero";

function photo(
  id: number,
  overrides: Partial<AuctionPhotoDto> = {},
): AuctionPhotoDto {
  return {
    publicId: `00000000-0000-0000-0000-${String(id).padStart(12, "0")}`,
    lightUrl: `https://cdn.example/${id}.jpg`,
    darkUrl: null,
    source: "SELLER_UPLOAD",
    sortOrder: id,
    ...overrides,
  };
}

// Dispatch a window-level keydown event with a controllable target. The
// page-level listener guards on `event.target` (skip when focus is in an
// input / textarea / contentEditable), so tests must be able to set a
// realistic target. fireEvent on `window` defaults to `document.body` as
// the target, which satisfies the non-form-input branch.
function dispatchWindowKey(
  key: string,
  init: KeyboardEventInit & { target?: EventTarget } = {},
) {
  const { target, ...rest } = init;
  const event = new KeyboardEvent("keydown", {
    key,
    bubbles: true,
    cancelable: true,
    ...rest,
  });
  if (target) {
    Object.defineProperty(event, "target", { value: target, writable: false });
  }
  act(() => {
    window.dispatchEvent(event);
  });
}

describe("AuctionHero", () => {
  it("renders the gradient placeholder when no photos and no snapshot", () => {
    renderWithProviders(
      <AuctionHero photos={[]} snapshotUrl={null} regionName="Heterocera" />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "placeholder");
    expect(hero).toHaveTextContent("Heterocera");
  });

  it("falls back to the parcel snapshot when photos are empty", () => {
    renderWithProviders(
      <AuctionHero
        photos={[]}
        snapshotUrl="https://cdn.example/snap.jpg"
        regionName="Heterocera"
      />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "snapshot");
    const img = hero.querySelector("img");
    expect(img).not.toBeNull();
    expect(img?.getAttribute("src")).toBe("https://cdn.example/snap.jpg");
  });

  it("renders a single-photo variant when exactly one photo is provided", () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1)]} snapshotUrl={null} />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "single");
    const img = screen.getByTestId("auction-hero-image");
    expect(img.getAttribute("src")).toBe("https://cdn.example/1.jpg");
  });

  it("sorts photos by sortOrder before selecting the hero", () => {
    renderWithProviders(
      <AuctionHero
        photos={[
          photo(30, { sortOrder: 2 }),
          photo(10, { sortOrder: 0 }),
          photo(20, { sortOrder: 1 }),
        ]}
        snapshotUrl={null}
      />,
    );
    // photo with sortOrder 0 should be the hero.
    expect(screen.getByTestId("auction-hero-image").getAttribute("src")).toBe(
      "https://cdn.example/10.jpg",
    );
  });

  it("opens the Lightbox at index 0 when the hero image is clicked (single-photo variant)", async () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1)]} snapshotUrl={null} />,
    );
    expect(screen.queryByTestId("lightbox")).toBeNull();
    await userEvent.click(screen.getByTestId("auction-hero"));
    expect(screen.getByTestId("lightbox")).toBeInTheDocument();
    expect(screen.getByTestId("lightbox-counter")).toHaveTextContent("1 / 1");
  });

  it("renders under the dark theme without crashing", () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1), photo(2)]} snapshotUrl={null} />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByTestId("auction-hero")).toHaveAttribute(
      "data-variant",
      "gallery",
    );
  });

  // ---------- multi-photo branch (gallery + thumb strip) ----------

  describe("multi-photo gallery", () => {
    const PHOTOS = [photo(1), photo(2), photo(3), photo(4)];

    it("renders hero showing photo 0 and marks thumb 0 as current", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/1.jpg");
      expect(screen.getByTestId("auction-hero-thumb-0")).toHaveAttribute(
        "aria-current",
        "true",
      );
      // No other thumb should claim current.
      expect(screen.getByTestId("auction-hero-thumb-1")).not.toHaveAttribute(
        "aria-current",
      );
    });

    it("clicking thumb 2 swaps the hero and moves aria-current", async () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      await userEvent.click(screen.getByTestId("auction-hero-thumb-2"));
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/3.jpg");
      expect(screen.getByTestId("auction-hero-thumb-2")).toHaveAttribute(
        "aria-current",
        "true",
      );
      expect(screen.getByTestId("auction-hero-thumb-0")).not.toHaveAttribute(
        "aria-current",
      );
    });

    it("clicking the hero opens the Lightbox at the selected index", async () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      await userEvent.click(screen.getByTestId("auction-hero-thumb-2"));
      await userEvent.click(screen.getByTestId("auction-hero-main"));
      expect(screen.getByTestId("lightbox")).toBeInTheDocument();
      expect(screen.getByTestId("lightbox-counter")).toHaveTextContent(
        "3 / 4",
      );
    });

    it("ArrowRight advances the hero", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      dispatchWindowKey("ArrowRight");
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/2.jpg");
      expect(screen.getByTestId("auction-hero-thumb-1")).toHaveAttribute(
        "aria-current",
        "true",
      );
    });

    it("ArrowLeft retreats the hero", async () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      // Move forward first so we have somewhere to retreat to without
      // exercising the wrap-around path.
      await userEvent.click(screen.getByTestId("auction-hero-thumb-2"));
      dispatchWindowKey("ArrowLeft");
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/2.jpg");
    });

    it("wraps from last to first on ArrowRight", async () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      await userEvent.click(screen.getByTestId("auction-hero-thumb-3"));
      dispatchWindowKey("ArrowRight");
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/1.jpg");
      expect(screen.getByTestId("auction-hero-thumb-0")).toHaveAttribute(
        "aria-current",
        "true",
      );
    });

    it("wraps from first to last on ArrowLeft", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      dispatchWindowKey("ArrowLeft");
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/4.jpg");
      expect(screen.getByTestId("auction-hero-thumb-3")).toHaveAttribute(
        "aria-current",
        "true",
      );
    });

    it("ignores ArrowRight when focus is inside a form input", () => {
      renderWithProviders(
        <div>
          <input data-testid="probe" />
          <AuctionHero photos={PHOTOS} snapshotUrl={null} />
        </div>,
      );
      const input = screen.getByTestId("probe");
      input.focus();
      dispatchWindowKey("ArrowRight", { target: input });
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/1.jpg");
    });

    it("ignores ArrowRight when a modifier key is held", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      dispatchWindowKey("ArrowRight", { metaKey: true });
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/1.jpg");
    });

    it("page-level ArrowRight is a no-op while the Lightbox is open", async () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      await userEvent.click(screen.getByTestId("auction-hero-main"));
      expect(screen.getByTestId("lightbox")).toBeInTheDocument();
      // The Lightbox's own handler advances its index; the page-level
      // handler must not double-advance the hero behind it. We assert by
      // closing the lightbox and verifying the hero is still on photo 0.
      dispatchWindowKey("ArrowRight");
      await userEvent.click(screen.getByTestId("lightbox-close"));
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/1.jpg");
    });

    it("clamps selectedIndex when the photos array shrinks", async () => {
      const { rerender } = renderWithProviders(
        <AuctionHero
          photos={[photo(1), photo(2), photo(3), photo(4), photo(5), photo(6)]}
          snapshotUrl={null}
        />,
      );
      await userEvent.click(screen.getByTestId("auction-hero-thumb-4"));
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/5.jpg");
      rerender(
        <AuctionHero
          photos={[photo(10), photo(11), photo(12)]}
          snapshotUrl={null}
        />,
      );
      // Index 4 no longer exists; render falls back to photo 0.
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/10.jpg");
      expect(screen.getByTestId("auction-hero-thumb-0")).toHaveAttribute(
        "aria-current",
        "true",
      );
    });

    it("after any navigation exactly one thumb has aria-current", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      dispatchWindowKey("ArrowRight");
      dispatchWindowKey("ArrowRight");
      const currents = screen
        .getAllByRole("button")
        .filter((b) => b.getAttribute("aria-current") === "true");
      expect(currents).toHaveLength(1);
      expect(currents[0]).toBe(screen.getByTestId("auction-hero-thumb-2"));
    });

    it("touch swipe left advances the hero", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      const main = screen.getByTestId("auction-hero-main");
      fireEvent.pointerDown(main, {
        clientX: 200,
        clientY: 100,
        pointerType: "touch",
      });
      fireEvent.pointerUp(main, {
        clientX: 120,
        clientY: 100,
        pointerType: "touch",
      });
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/2.jpg");
      // Swipe must not open the Lightbox.
      expect(screen.queryByTestId("lightbox")).toBeNull();
    });

    it("touch swipe right retreats the hero", async () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      await userEvent.click(screen.getByTestId("auction-hero-thumb-2"));
      const main = screen.getByTestId("auction-hero-main");
      fireEvent.pointerDown(main, {
        clientX: 120,
        clientY: 100,
        pointerType: "touch",
      });
      fireEvent.pointerUp(main, {
        clientX: 200,
        clientY: 100,
        pointerType: "touch",
      });
      expect(
        screen.getByTestId("auction-hero-image").getAttribute("src"),
      ).toBe("https://cdn.example/2.jpg");
      expect(screen.queryByTestId("lightbox")).toBeNull();
    });

    it("a tap (no horizontal delta) opens the Lightbox", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      const main = screen.getByTestId("auction-hero-main");
      fireEvent.pointerDown(main, {
        clientX: 150,
        clientY: 100,
        pointerType: "touch",
      });
      fireEvent.pointerUp(main, {
        clientX: 150,
        clientY: 100,
        pointerType: "touch",
      });
      expect(screen.getByTestId("lightbox")).toBeInTheDocument();
      expect(screen.getByTestId("lightbox-counter")).toHaveTextContent(
        "1 / 4",
      );
    });

    it("counter overlay shows '{selected+1} / {total}' and updates with navigation", () => {
      renderWithProviders(<AuctionHero photos={PHOTOS} snapshotUrl={null} />);
      const counter = screen.getByTestId("auction-hero-counter");
      expect(counter).toHaveTextContent("1 / 4");
      dispatchWindowKey("ArrowRight");
      expect(screen.getByTestId("auction-hero-counter")).toHaveTextContent(
        "2 / 4",
      );
    });
  });
});
