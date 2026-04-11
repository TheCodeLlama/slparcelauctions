import { describe, expect, it } from "vitest";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { renderWithProviders, screen } from "./render";

describe("renderWithProviders", () => {
  it("renders a child element inside the provider stack without crashing", () => {
    renderWithProviders(<div data-testid="probe">hello</div>);
    expect(screen.getByTestId("probe")).toHaveTextContent("hello");
  });

  it("preserves the same QueryClient instance across re-renders", () => {
    const capturedRef: { current: QueryClient | null } = { current: null };

    function Probe() {
      const client = useQueryClient();
      // Capture the latest client instance on every render. The test then
      // checks that it's the same object across rerender(), which would fail
      // if the wrapper reconstructed the QueryClient on each render.
      // eslint-disable-next-line react-hooks/immutability
      capturedRef.current = client;
      return <div data-testid="probe">probed</div>;
    }

    const { rerender } = renderWithProviders(<Probe />);
    const firstClient = capturedRef.current;
    rerender(<Probe />);
    const secondClient = capturedRef.current;

    expect(firstClient).not.toBeNull();
    expect(secondClient).not.toBeNull();
    expect(Object.is(firstClient, secondClient)).toBe(true);
  });
});
