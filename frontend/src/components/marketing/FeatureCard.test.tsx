// frontend/src/components/marketing/FeatureCard.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { Zap } from "@/components/ui/icons";
import { FeatureCard } from "./FeatureCard";

describe("FeatureCard", () => {
  it("renders title, body, and icon slot", () => {
    renderWithProviders(
      <FeatureCard
        icon={<Zap data-testid="feature-icon" />}
        title="Real-Time Bidding"
        body="Our low-latency engine updates bids in milliseconds."
      />
    );
    expect(screen.getByTestId("feature-icon")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Real-Time Bidding" })).toBeInTheDocument();
    expect(
      screen.getByText(/our low-latency engine updates bids/i)
    ).toBeInTheDocument();
  });

  it("applies md:col-span-2 when size=lg and does not when size=sm", () => {
    const { container: largeContainer } = renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Large"
        body="A large card."
        size="lg"
      />
    );
    // next-themes injects a <script> at the top of the container, so the card
    // root is the first <div>, not the firstChild. Use querySelector("div").
    expect(largeContainer.querySelector("div")).toHaveClass("md:col-span-2");

    const { container: smallContainer } = renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Small"
        body="A small card."
        size="sm"
      />
    );
    expect(smallContainer.querySelector("div")).not.toHaveClass("md:col-span-2");
  });

  it("applies variant-specific background classes", () => {
    const { container: surfaceContainer } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="S" body="." variant="surface" />
    );
    expect(surfaceContainer.querySelector("div")).toHaveClass("bg-surface-container");

    const { container: primaryContainer } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="P" body="." variant="primary" />
    );
    expect(primaryContainer.querySelector("div")).toHaveClass("bg-primary-container");

    const { container: darkContainer } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="D" body="." variant="dark" />
    );
    expect(darkContainer.querySelector("div")).toHaveClass("bg-inverse-surface");
  });

  it("renders no decorative image when backgroundImage prop is omitted", () => {
    const { container } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="X" body="." />
    );
    expect(container.querySelector("img")).toBeNull();
  });

  it("renders the light backgroundImage when theme=light", async () => {
    renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Real-Time Bidding"
        body="."
        backgroundImage={{
          light: "/landing/bidding-bg.png",
          dark: "/landing/bidding-bg.png",
        }}
      />,
      { theme: "light", forceTheme: true }
    );
    await waitFor(() => {
      const img = document.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.src).toContain("bidding-bg.png");
    });
  });

  it("renders the dark backgroundImage when theme=dark and the two sources differ", async () => {
    // Construct a synthetic dark-only path to prove the swap happens even if
    // real usage ships the same file for both themes.
    renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Real-Time Bidding"
        body="."
        backgroundImage={{
          light: "/landing/hero-parcel-light.png",
          dark: "/landing/hero-parcel-dark.png",
        }}
      />,
      { theme: "dark", forceTheme: true }
    );
    await waitFor(() => {
      const img = document.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.src).toContain("hero-parcel-dark.png");
    });
  });
});
