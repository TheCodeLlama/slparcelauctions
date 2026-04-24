import { describe, it, expect } from "vitest";
import { viewerProtocolUrl, mapUrl } from "./slurl";

describe("viewerProtocolUrl", () => {
  it("emits raw region segment including spaces", () => {
    expect(viewerProtocolUrl("Bay City", 20, 30, 40)).toBe(
      "secondlife:///app/teleport/Bay City/20/30/40",
    );
  });

  it("preserves apostrophes raw", () => {
    expect(viewerProtocolUrl("O'Malley's Point", 10, 20, 0)).toBe(
      "secondlife:///app/teleport/O'Malley's Point/10/20/0",
    );
  });

  it("preserves unicode characters raw", () => {
    expect(viewerProtocolUrl("Königsberg", 50, 60, 70)).toBe(
      "secondlife:///app/teleport/Königsberg/50/60/70",
    );
  });

  it("falls back to region-centre 128/128/0 when positions are null", () => {
    expect(viewerProtocolUrl("Heterocera", null, null, null)).toBe(
      "secondlife:///app/teleport/Heterocera/128/128/0",
    );
  });

  it("falls back to region-centre 128/128 when positions are 0 (but keeps z=0)", () => {
    // X/Y of 0 is the corner of the region — the SL convention is that
    // callers with no specific spawn point want centre, which is 128/128.
    expect(viewerProtocolUrl("Heterocera", 0, 0, 0)).toBe(
      "secondlife:///app/teleport/Heterocera/128/128/0",
    );
  });

  it("accepts undefined and mirrors null behavior", () => {
    expect(viewerProtocolUrl("Heterocera", undefined, undefined, undefined)).toBe(
      "secondlife:///app/teleport/Heterocera/128/128/0",
    );
  });
});

describe("mapUrl", () => {
  it("encodes spaces in the region segment as %20", () => {
    expect(mapUrl("Bay City", 20, 30, 40)).toBe(
      "https://maps.secondlife.com/secondlife/Bay%20City/20/30/40",
    );
  });

  it("percent-encodes apostrophes", () => {
    expect(mapUrl("O'Malley's Point", 10, 20, 0)).toBe(
      "https://maps.secondlife.com/secondlife/O'Malley's%20Point/10/20/0",
    );
  });

  it("percent-encodes unicode characters", () => {
    expect(mapUrl("Königsberg", 50, 60, 70)).toBe(
      "https://maps.secondlife.com/secondlife/K%C3%B6nigsberg/50/60/70",
    );
  });

  it("falls back to region-centre when positions are null", () => {
    expect(mapUrl("Heterocera", null, null, null)).toBe(
      "https://maps.secondlife.com/secondlife/Heterocera/128/128/0",
    );
  });

  it("falls back to region-centre when positions are 0", () => {
    expect(mapUrl("Heterocera", 0, 0, 0)).toBe(
      "https://maps.secondlife.com/secondlife/Heterocera/128/128/0",
    );
  });

  it("accepts undefined positions", () => {
    expect(mapUrl("Heterocera", undefined, undefined, undefined)).toBe(
      "https://maps.secondlife.com/secondlife/Heterocera/128/128/0",
    );
  });
});
