import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor, fireEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { DistanceSearchBlock } from "./DistanceSearchBlock";

// The shared handler in handlers.ts already answers
// */api/v1/search/suggest and honors regionsOnly. These per-test
// overrides pin exact region rows so the listbox content is
// deterministic.
function suggestRegions(names: string[]) {
  server.use(
    http.get("*/api/v1/search/suggest", () =>
      HttpResponse.json({
        listings: [],
        regions: names.map((name) => ({ name, activeAuctionCount: 0 })),
        totalListings: 0,
      }),
    ),
  );
}

describe("DistanceSearchBlock", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("debounce-fetches suggestions and renders a listbox", async () => {
    suggestRegions(["Tula", "Tula Beach"]);
    renderWithProviders(<DistanceSearchBlock query={{}} onChange={vi.fn()} />);
    const input = screen.getByLabelText(/region name/i);
    expect(input).toHaveAttribute("role", "combobox");
    fireEvent.change(input, { target: { value: "tul" } });
    await vi.advanceTimersByTimeAsync(300);
    const listbox = await screen.findByRole("listbox");
    expect(listbox).toBeInTheDocument();
    expect(screen.getAllByRole("option")).toHaveLength(2);
    expect(input).toHaveAttribute("aria-expanded", "true");
  });

  it("does not fetch below 2 chars", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/search/suggest", () => {
        calls += 1;
        return HttpResponse.json({ listings: [], regions: [], totalListings: 0 });
      }),
    );
    renderWithProviders(<DistanceSearchBlock query={{}} onChange={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/region name/i), {
      target: { value: "t" },
    });
    await vi.advanceTimersByTimeAsync(400);
    expect(calls).toBe(0);
  });

  it("ArrowDown + Enter selects and commits the region + triggers search", async () => {
    const onChange = vi.fn();
    suggestRegions(["Tula", "Luna"]);
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "lu" } });
    await vi.advanceTimersByTimeAsync(300);
    await screen.findByRole("listbox");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({ nearRegion: "Luna" }),
    );
  });

  it("click-select commits the region", async () => {
    const onChange = vi.fn();
    suggestRegions(["Da Boom"]);
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "boom" } });
    await vi.advanceTimersByTimeAsync(300);
    // The option commits on mousedown (fires before the input's blur,
    // so the selection lands before blur could revert the value).
    fireEvent.mouseDown(await screen.findByRole("option", { name: "Da Boom" }));
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({ nearRegion: "Da Boom" }),
    );
  });

  it("typing without selecting does NOT commit (no doomed 400 search)", async () => {
    const onChange = vi.fn();
    suggestRegions(["Tula"]);
    renderWithProviders(
      <DistanceSearchBlock query={{}} onChange={onChange} />,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "Da" } });
    fireEvent.change(input, { target: { value: "Da Boo" } });
    fireEvent.change(input, { target: { value: "Da Boomtypo" } });
    await vi.advanceTimersByTimeAsync(400);
    fireEvent.keyDown(input, { key: "Enter" });
    fireEvent.blur(input);
    await vi.advanceTimersByTimeAsync(50);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("blurring un-selected text reverts the input to the last committed region", async () => {
    renderWithProviders(
      <DistanceSearchBlock
        query={{ nearRegion: "Tula" }}
        onChange={vi.fn()}
      />,
    );
    const input = screen.getByLabelText<HTMLInputElement>(/region name/i);
    expect(input.value).toBe("Tula");
    fireEvent.change(input, { target: { value: "garbled" } });
    fireEvent.blur(input);
    await waitFor(() => expect(input.value).toBe("Tula"));
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

  it("Escape closes the listbox", async () => {
    suggestRegions(["Tula"]);
    renderWithProviders(<DistanceSearchBlock query={{}} onChange={vi.fn()} />);
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "tu" } });
    await vi.advanceTimersByTimeAsync(300);
    await screen.findByRole("listbox");
    fireEvent.keyDown(input, { key: "Escape" });
    await waitFor(() =>
      expect(screen.queryByRole("listbox")).not.toBeInTheDocument(),
    );
  });

  it("outside click closes the listbox", async () => {
    suggestRegions(["Tula"]);
    renderWithProviders(
      <div>
        <DistanceSearchBlock query={{}} onChange={vi.fn()} />
        <button type="button">outside</button>
      </div>,
    );
    const input = screen.getByLabelText(/region name/i);
    fireEvent.change(input, { target: { value: "tu" } });
    await vi.advanceTimersByTimeAsync(300);
    await screen.findByRole("listbox");
    fireEvent.mouseDown(screen.getByRole("button", { name: "outside" }));
    await waitFor(() =>
      expect(screen.queryByRole("listbox")).not.toBeInTheDocument(),
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
