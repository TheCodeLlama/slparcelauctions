import { describe, it, expect, vi } from "vitest";
import { fireEvent } from "@testing-library/react";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { DistanceSearchBlock } from "./DistanceSearchBlock";

describe("DistanceSearchBlock", () => {
  it("does not commit while typing (no per-keystroke / debounce commit)", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    // Simulate typing a region incrementally — every partial used to
    // 400-storm the backend via the old 300ms auto-commit.
    fireEvent.change(input, { target: { value: "D" } });
    fireEvent.change(input, { target: { value: "Da" } });
    fireEvent.change(input, { target: { value: "Da Boom" } });
    // Give any stale timer a chance to fire — it must not.
    await new Promise((r) => setTimeout(r, 400));
    expect(onChange).not.toHaveBeenCalled();
  });

  it("commits the typed value on blur", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "Tula" } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.blur(input);
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ nearRegion: "Tula" }),
      ),
    );
  });

  it("commits the typed value on Enter keydown", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "Tula" } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ nearRegion: "Tula" }),
      ),
    );
  });

  it("clearing the field then blurring removes the filter", async () => {
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

  it("clearing the field then Enter removes the filter", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock
        query={{ nearRegion: "Tula", distance: 5 }}
        onChange={onChange}
      />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "" } });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        nearRegion: undefined,
        distance: undefined,
      }),
    );
  });

  it("distance slider still commits when a region is set", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DistanceSearchBlock
        query={{ nearRegion: "Tula", distance: 10 }}
        onChange={onChange}
      />,
    );
    // RangeSlider renders [min, max] as two native range inputs; the
    // max-distance thumb is the second one. Driving it changes distance.
    const maxThumb = screen.getByLabelText(/maximum distance/i);
    fireEvent.change(maxThumb, { target: { value: "5" } });
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ distance: 5 }),
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

  it("renders in dark mode", () => {
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={() => {}} />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByLabelText(/region name/i)).toBeInTheDocument();
  });
});
