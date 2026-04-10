// frontend/src/test/render.smoke.test.tsx
import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "./render";

describe("renderWithProviders", () => {
  it("renders a child element inside the provider stack without crashing", () => {
    renderWithProviders(<div data-testid="probe">hello</div>);
    expect(screen.getByTestId("probe")).toHaveTextContent("hello");
  });
});
