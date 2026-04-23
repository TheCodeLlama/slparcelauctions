import { describe, expect, it } from "vitest";
import {
  isActivatePollingStop,
  isEditable,
  isPreActive,
  isTerminal,
} from "./auctionStatus";

describe("auctionStatus predicates", () => {
  describe("isTerminal", () => {
    it("returns false for ACTIVE (live, not terminal)", () => {
      expect(isTerminal("ACTIVE")).toBe(false);
    });

    it("returns false for the escrow/transfer in-flight states", () => {
      expect(isTerminal("ESCROW_PENDING")).toBe(false);
      expect(isTerminal("ESCROW_FUNDED")).toBe(false);
      expect(isTerminal("TRANSFER_PENDING")).toBe(false);
    });

    it("returns false for ENDED (cycle done but escrow/transfer may still run)", () => {
      expect(isTerminal("ENDED")).toBe(false);
    });

    it("returns true for genuinely terminal statuses", () => {
      expect(isTerminal("CANCELLED")).toBe(true);
      expect(isTerminal("SUSPENDED")).toBe(true);
      expect(isTerminal("EXPIRED")).toBe(true);
      expect(isTerminal("COMPLETED")).toBe(true);
      expect(isTerminal("DISPUTED")).toBe(true);
    });

    it("returns false for pre-active draft statuses", () => {
      expect(isTerminal("DRAFT")).toBe(false);
      expect(isTerminal("DRAFT_PAID")).toBe(false);
      expect(isTerminal("VERIFICATION_PENDING")).toBe(false);
      expect(isTerminal("VERIFICATION_FAILED")).toBe(false);
    });
  });

  describe("isActivatePollingStop", () => {
    it("returns true for ACTIVE (activate flow succeeded)", () => {
      expect(isActivatePollingStop("ACTIVE")).toBe(true);
    });

    it("returns false for VERIFICATION_PENDING (still working)", () => {
      expect(isActivatePollingStop("VERIFICATION_PENDING")).toBe(false);
    });

    it("returns false for other pre-active statuses", () => {
      expect(isActivatePollingStop("DRAFT")).toBe(false);
      expect(isActivatePollingStop("DRAFT_PAID")).toBe(false);
      expect(isActivatePollingStop("VERIFICATION_FAILED")).toBe(false);
    });

    it("returns true for escrow/transfer states (activate page handed off)", () => {
      expect(isActivatePollingStop("ESCROW_PENDING")).toBe(true);
      expect(isActivatePollingStop("ESCROW_FUNDED")).toBe(true);
      expect(isActivatePollingStop("TRANSFER_PENDING")).toBe(true);
    });

    it("returns true for all truly-terminal statuses", () => {
      expect(isActivatePollingStop("CANCELLED")).toBe(true);
      expect(isActivatePollingStop("SUSPENDED")).toBe(true);
      expect(isActivatePollingStop("EXPIRED")).toBe(true);
      expect(isActivatePollingStop("COMPLETED")).toBe(true);
      expect(isActivatePollingStop("DISPUTED")).toBe(true);
      expect(isActivatePollingStop("ENDED")).toBe(true);
    });
  });

  describe("isPreActive", () => {
    it("returns true for draft/verification statuses", () => {
      expect(isPreActive("DRAFT")).toBe(true);
      expect(isPreActive("DRAFT_PAID")).toBe(true);
      expect(isPreActive("VERIFICATION_PENDING")).toBe(true);
      expect(isPreActive("VERIFICATION_FAILED")).toBe(true);
    });

    it("returns false for ACTIVE and beyond", () => {
      expect(isPreActive("ACTIVE")).toBe(false);
      expect(isPreActive("COMPLETED")).toBe(false);
    });
  });

  describe("isEditable", () => {
    it("permits edits only for DRAFT and DRAFT_PAID", () => {
      expect(isEditable("DRAFT")).toBe(true);
      expect(isEditable("DRAFT_PAID")).toBe(true);
    });

    it("blocks edits once verification has started", () => {
      expect(isEditable("VERIFICATION_PENDING")).toBe(false);
      expect(isEditable("VERIFICATION_FAILED")).toBe(false);
      expect(isEditable("ACTIVE")).toBe(false);
    });
  });
});
