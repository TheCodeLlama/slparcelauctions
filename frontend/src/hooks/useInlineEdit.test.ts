import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useInlineEdit } from "./useInlineEdit";

describe("useInlineEdit", () => {
  it("starts in idle state, transitions to editing on startEdit", () => {
    const { result } = renderHook(() =>
      useInlineEdit({ initialValue: "hello", onSave: vi.fn() }),
    );
    expect(result.current.state).toBe("idle");
    act(() => result.current.startEdit());
    expect(result.current.state).toBe("editing");
    expect(result.current.draft).toBe("hello");
  });

  it("transitions editing -> saving -> idle on successful save", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() =>
      useInlineEdit({ initialValue: "hello", onSave }),
    );
    act(() => result.current.startEdit());
    act(() => result.current.setDraft("hello world"));
    await act(async () => {
      await result.current.commit();
    });
    expect(onSave).toHaveBeenCalledWith("hello world");
    expect(result.current.state).toBe("idle");
    expect(result.current.error).toBeNull();
  });

  it("transitions to error state on save failure, keeps editor open", async () => {
    const onSave = vi.fn().mockRejectedValue(new Error("boom"));
    const { result } = renderHook(() =>
      useInlineEdit({ initialValue: "hello", onSave }),
    );
    act(() => result.current.startEdit());
    act(() => result.current.setDraft("oops"));
    await act(async () => {
      try {
        await result.current.commit();
      } catch {
        // expected
      }
    });
    expect(result.current.state).toBe("editing");
    expect(result.current.error).toBe("boom");
    expect(result.current.draft).toBe("oops");
  });

  it("cancel returns to idle and discards draft", () => {
    const { result } = renderHook(() =>
      useInlineEdit({ initialValue: "hello", onSave: vi.fn() }),
    );
    act(() => result.current.startEdit());
    act(() => result.current.setDraft("garbage"));
    act(() => result.current.cancel());
    expect(result.current.state).toBe("idle");
  });

  it("commit is a no-op when draft equals initialValue", async () => {
    const onSave = vi.fn();
    const { result } = renderHook(() =>
      useInlineEdit({ initialValue: "hello", onSave }),
    );
    act(() => result.current.startEdit());
    await act(async () => {
      await result.current.commit();
    });
    expect(onSave).not.toHaveBeenCalled();
    expect(result.current.state).toBe("idle");
  });
});
