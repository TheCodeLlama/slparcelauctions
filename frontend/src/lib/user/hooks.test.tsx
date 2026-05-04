import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor, act } from "@testing-library/react";
import { server } from "@/test/msw/server";
import { userHandlers, verificationHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import { makeWrapper } from "@/test/render";
import {
  useCurrentUser,
  useUpdateProfile,
  useUploadAvatar,
  useActiveVerificationCode,
  useGenerateVerificationCode,
} from "./hooks";

// These tests exercise the network layer through MSW. They rely on `useAuth`
// returning `{ status: "authenticated" }`, which is the default in the
// test wrapper's authenticated mode.
describe("user hooks", () => {
  describe("useCurrentUser", () => {
    it("fetches /me when session is authenticated", async () => {
      server.use(userHandlers.meVerified());
      const { result } = renderHook(() => useCurrentUser(), {
        wrapper: makeWrapper({ auth: "authenticated" }),
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.publicId).toBe(mockVerifiedCurrentUser.publicId);
      expect(result.current.data?.updatedAt).toBe(mockVerifiedCurrentUser.updatedAt);
    });

    it("is disabled when session is not authenticated", async () => {
      server.use(userHandlers.meVerified());
      const { result } = renderHook(() => useCurrentUser(), {
        wrapper: makeWrapper({ auth: "anonymous" }),
      });
      await waitFor(() => expect(result.current.fetchStatus).toBe("idle"));
      expect(result.current.data).toBeUndefined();
    });
  });

  describe("useUpdateProfile", () => {
    it("invalidates currentUser on success", async () => {
      server.use(userHandlers.meVerified(), userHandlers.updateMeSuccess());
      const { result } = renderHook(
        () => ({
          me: useCurrentUser(),
          update: useUpdateProfile(),
        }),
        { wrapper: makeWrapper({ auth: "authenticated" }) }
      );
      await waitFor(() => expect(result.current.me.isSuccess).toBe(true));

      // Swap the /me handler so the post-invalidation refetch returns updated data.
      const updated = {
        ...mockVerifiedCurrentUser,
        displayName: "New Name",
        bio: "New bio",
      };
      server.use(
        http.get("*/api/v1/users/me", () => HttpResponse.json(updated))
      );

      await act(async () => {
        await result.current.update.mutateAsync({
          displayName: "New Name",
          bio: "New bio",
        });
      });

      await waitFor(() => expect(result.current.me.data?.displayName).toBe("New Name"));
    });
  });

  describe("useUploadAvatar", () => {
    it("invalidates currentUser on success", async () => {
      const initial = { ...mockVerifiedCurrentUser, updatedAt: "2026-04-14T12:00:00Z" };
      server.use(userHandlers.meVerified(initial), userHandlers.uploadAvatarSuccess(initial));

      const { result } = renderHook(
        () => ({
          me: useCurrentUser(),
          upload: useUploadAvatar(),
        }),
        { wrapper: makeWrapper({ auth: "authenticated" }) }
      );
      await waitFor(() => expect(result.current.me.isSuccess).toBe(true));

      // Swap the /me handler so the post-invalidation refetch returns updated timestamp.
      const after = { ...initial, updatedAt: "2026-04-14T13:00:00Z" };
      server.use(
        http.get("*/api/v1/users/me", () => HttpResponse.json(after))
      );

      const file = new File(["x"], "avatar.png", { type: "image/png" });
      await act(async () => {
        await result.current.upload.mutateAsync(file);
      });

      await waitFor(() =>
        expect(result.current.me.data?.updatedAt).not.toBe(initial.updatedAt)
      );
    });
  });

  describe("useActiveVerificationCode", () => {
    it("returns null on 404", async () => {
      server.use(verificationHandlers.activeNone());
      const { result } = renderHook(() => useActiveVerificationCode(), {
        wrapper: makeWrapper({ auth: "authenticated" }),
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toBeNull();
    });

    it("returns the active code when present", async () => {
      server.use(verificationHandlers.activeExists("987654", "2026-04-14T21:30:00Z"));
      const { result } = renderHook(() => useActiveVerificationCode(), {
        wrapper: makeWrapper({ auth: "authenticated" }),
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.code).toBe("987654");
    });
  });

  describe("useGenerateVerificationCode", () => {
    it("invalidates the active code query on success", async () => {
      server.use(
        verificationHandlers.activeNone(),
        verificationHandlers.generateSuccess("111222", "2026-04-14T21:30:00Z")
      );
      const { result } = renderHook(
        () => ({
          active: useActiveVerificationCode(),
          generate: useGenerateVerificationCode(),
        }),
        { wrapper: makeWrapper({ auth: "authenticated" }) }
      );
      await waitFor(() => expect(result.current.active.isSuccess).toBe(true));
      expect(result.current.active.data).toBeNull();

      // Swap the handler so the invalidated refetch returns the new code.
      server.use(verificationHandlers.activeExists("111222", "2026-04-14T21:30:00Z"));

      await act(async () => {
        await result.current.generate.mutateAsync();
      });

      await waitFor(() => expect(result.current.active.data?.code).toBe("111222"));
    });
  });
});
