import { describe, it, expect, vi } from "vitest";
import { fireEvent } from "@testing-library/react";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { DistanceSearchBlock } from "./DistanceSearchBlock";

describe("DistanceSearchBlock", () => {
  it("debounces input and emits nearRegion after 300ms", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "Tula" } });
    // Before debounce fires, no emission. Poll briefly to avoid a race
    // with a microtask flush that happens synchronously.
    expect(onChange).not.toHaveBeenCalled();
    await waitFor(
      () =>
        expect(onChange).toHaveBeenCalledWith(
          expect.objectContaining({ nearRegion: "Tula" }),
        ),
      { timeout: 1000 },
    );
  });

  it("commits immediately on blur", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "Tula" } });
    fireEvent.blur(input);
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ nearRegion: "Tula" }),
      ),
    );
  });

  it("surfaces REGION_NOT_FOUND inline", () => {
    renderWithProviders(
      <DistanceSearchBlock
        query={{ nearRegion: "Bogus" }}
        onChange={() => {}}
        errorCode="REGION_NOT_FOUND"
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      /couldn't locate that region/i,
    );
  });

  it("surfaces REGION_LOOKUP_UNAVAILABLE inline", () => {
    renderWithProviders(
      <DistanceSearchBlock
        query={{ nearRegion: "Tula" }}
        onChange={() => {}}
        errorCode="REGION_LOOKUP_UNAVAILABLE"
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      /temporarily unavailable/i,
    );
  });

  it("clearing the region also clears the distance", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock
        query={{ nearRegion: "Tula", distance: 5 }}
        onChange={onChange}
      />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "" } });
    fireEvent.blur(input);
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        nearRegion: undefined,
        distance: undefined,
      }),
    );
  });
});
