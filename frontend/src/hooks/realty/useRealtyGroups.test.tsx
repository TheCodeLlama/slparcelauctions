import { describe, it, expect, vi } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import {
  useRealtyGroup,
  useRealtyGroupBySlug,
  useRealtyGroupMembers,
  useRealtyGroupInvitations,
  useMyRealtyGroups,
  useMyInvitations,
  useUserRealtyGroups,
  useAdminRealtyGroupsList,
  useAdminRealtyGroup,
  useCreateGroup,
  useUpdateGroup,
  useDissolveGroup,
  useUploadLogo,
  useUploadCover,
  useRemoveMember,
  useUpdatePermissions,
  useLeaveGroup,
  useTransferLeadership,
  useInvite,
  useRevokeInvitation,
  useAcceptInvitation,
  useDeclineInvitation,
  useAdminUpdateGroup,
  useAdminDissolveGroup,
  useAdminRemoveMember,
  realtyQueryKeys,
} from "./useRealtyGroups";
import type {
  AgentCardDto,
  InvitationDto,
  RealtyGroupPublicDto,
  RealtyGroupRowDto,
  RealtyGroupSummaryDto,
  UserRealtyGroupAffiliationDto,
} from "@/types/realty";
import type { Page } from "@/types/page";

// ─── Fixture factories ─────────────────────────────────────────────────────

function makeGroup(
  overrides: Partial<RealtyGroupPublicDto> = {},
): RealtyGroupPublicDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    description: null,
    website: null,
    logoUrl: null,
    coverUrl: null,
    memberSince: "2026-04-01T10:00:00Z",
    leader: {
      userPublicId: "11111111-1111-1111-1111-111111111111",
      displayName: "Leader",
      avatarUrl: null,
    },
    agents: [],
    memberSeatLimit: 50,
    memberCount: 1,
    ...overrides,
  };
}

function makeSummary(
  overrides: Partial<RealtyGroupSummaryDto> = {},
): RealtyGroupSummaryDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    logoUrl: null,
    memberCount: 1,
    memberSince: "2026-04-01T10:00:00Z",
    ...overrides,
  };
}

function makeInvitation(
  overrides: Partial<InvitationDto> = {},
): InvitationDto {
  return {
    publicId: "22222222-2222-2222-2222-222222222222",
    groupPublicId: "00000000-0000-0000-0000-000000000001",
    groupName: "Mainland Realty",
    groupSlug: "mainland-realty",
    invitedByPublicId: "11111111-1111-1111-1111-111111111111",
    invitedByDisplayName: "Leader",
    permissions: ["INVITE_AGENTS"],
    status: "PENDING",
    expiresAt: "2026-05-10T10:00:00Z",
    createdAt: "2026-05-03T10:00:00Z",
    respondedAt: null,
    ...overrides,
  };
}

function makeAgent(overrides: Partial<AgentCardDto> = {}): AgentCardDto {
  return {
    memberPublicId: "33333333-3333-3333-3333-333333333333",
    userPublicId: "44444444-4444-4444-4444-444444444444",
    displayName: "Agent",
    avatarUrl: null,
    role: "AGENT",
    permissions: ["INVITE_AGENTS"],
    joinedAt: "2026-04-15T10:00:00Z",
    agentCommissionRate: null,
    ...overrides,
  };
}

function makeRow(overrides: Partial<RealtyGroupRowDto> = {}): RealtyGroupRowDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    leaderPublicId: "11111111-1111-1111-1111-111111111111",
    leaderDisplayName: "Leader",
    memberCount: 1,
    dissolved: false,
    createdAt: "2026-04-01T10:00:00Z",
    dissolvedAt: null,
    ...overrides,
  };
}

function makeAffiliation(
  overrides: Partial<UserRealtyGroupAffiliationDto> = {},
): UserRealtyGroupAffiliationDto {
  return {
    groupPublicId: "00000000-0000-0000-0000-000000000001",
    groupName: "Mainland Realty",
    groupSlug: "mainland-realty",
    logoUrl: null,
    role: "LEADER",
    ...overrides,
  };
}

// ─── Test harness ──────────────────────────────────────────────────────────

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}

function newQc() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const SLUG = "mainland-realty";
const MEMBER_ID = "33333333-3333-3333-3333-333333333333";
const INV_ID = "22222222-2222-2222-2222-222222222222";
const USER_ID = "44444444-4444-4444-4444-444444444444";

/**
 * Build an RFC 7807-ish error response body matching the backend's
 * `ProblemDetail` + `code` extension. The realty error mapper reads
 * `problem.code` and `problem.cooldownEndsAt`.
 */
function problemBody(status: number, code: string, extra: Record<string, unknown> = {}) {
  return {
    type: "https://slpa.example/problems/realty",
    title: code,
    status,
    detail: code,
    code,
    ...extra,
  };
}

// ─── Query tests ───────────────────────────────────────────────────────────

describe("realty query hooks", () => {
  it("useRealtyGroup loads + caches a group by publicId", async () => {
    const group = makeGroup();
    server.use(
      http.get(`*/api/v1/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(group),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useRealtyGroup(GROUP_ID), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.slug).toBe("mainland-realty");
    expect(qc.getQueryData(realtyQueryKeys.group(GROUP_ID))).toBeDefined();
  });

  it("useRealtyGroup is disabled when publicId is undefined", () => {
    const qc = newQc();
    const { result } = renderHook(() => useRealtyGroup(undefined), {
      wrapper: makeWrapper(qc),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("useRealtyGroupBySlug loads by slug", async () => {
    const group = makeGroup();
    server.use(
      http.get(`*/api/v1/realty-groups/by-slug/${SLUG}`, () =>
        HttpResponse.json(group),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useRealtyGroupBySlug(SLUG), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.publicId).toBe(GROUP_ID);
  });

  it("useRealtyGroupMembers returns the roster", async () => {
    server.use(
      http.get(`*/api/v1/realty-groups/${GROUP_ID}/members`, () =>
        HttpResponse.json([makeAgent()]),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useRealtyGroupMembers(GROUP_ID), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it("useRealtyGroupInvitations returns pending invites", async () => {
    server.use(
      http.get(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, () =>
        HttpResponse.json([makeInvitation()]),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useRealtyGroupInvitations(GROUP_ID), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0]?.status).toBe("PENDING");
  });

  it("useMyRealtyGroups loads dashboard summary list", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json([makeSummary()]),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useMyRealtyGroups(), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it("useMyInvitations loads caller's pending invitations", async () => {
    server.use(
      http.get("*/api/v1/me/invitations", () =>
        HttpResponse.json([makeInvitation()]),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useMyInvitations(), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it("useUserRealtyGroups loads affiliations for a user", async () => {
    server.use(
      http.get(`*/api/v1/users/${USER_ID}/realty-groups`, () =>
        HttpResponse.json([makeAffiliation()]),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUserRealtyGroups(USER_ID), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0]?.role).toBe("LEADER");
  });

  it("useAdminRealtyGroupsList loads paged admin rows", async () => {
    const page: Page<RealtyGroupRowDto> = {
      content: [makeRow()],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 25,
    };
    server.use(
      http.get("*/api/v1/admin/realty-groups", () =>
        HttpResponse.json(page),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(
      () =>
        useAdminRealtyGroupsList({ status: "active", page: 0, size: 25 }),
      { wrapper: makeWrapper(qc) },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.totalElements).toBe(1);
  });

  it("useAdminRealtyGroup loads admin detail", async () => {
    server.use(
      http.get(`*/api/v1/admin/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(makeGroup()),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAdminRealtyGroup(GROUP_ID), {
      wrapper: makeWrapper(qc),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.publicId).toBe(GROUP_ID);
  });
});

// ─── Mutation: success + invalidation ──────────────────────────────────────

describe("realty mutation hooks — success + invalidation", () => {
  it("useCreateGroup invalidates the realty tree on success", async () => {
    server.use(
      http.post("*/api/v1/realty-groups", () =>
        HttpResponse.json(makeGroup()),
      ),
    );
    const qc = newQc();
    const spy = vi.spyOn(qc, "invalidateQueries");
    const { result } = renderHook(() => useCreateGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({ name: "Mainland Realty" });
    });
    const keys = spy.mock.calls.map(
      (c) => (c[0] as { queryKey?: unknown }).queryKey,
    );
    expect(keys).toContainEqual(realtyQueryKeys.all);
  });

  it("useUpdateGroup invalidates the realty tree on success", async () => {
    server.use(
      http.patch(`*/api/v1/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(makeGroup()),
      ),
    );
    const qc = newQc();
    const spy = vi.spyOn(qc, "invalidateQueries");
    const { result } = renderHook(() => useUpdateGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        body: { description: "new" },
      });
    });
    const keys = spy.mock.calls.map(
      (c) => (c[0] as { queryKey?: unknown }).queryKey,
    );
    expect(keys).toContainEqual(realtyQueryKeys.all);
  });

  it("useDissolveGroup succeeds on 204", async () => {
    server.use(
      http.delete(
        `*/api/v1/realty-groups/${GROUP_ID}`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useDissolveGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(GROUP_ID);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useUploadLogo posts multipart and returns the updated group", async () => {
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/logo`, () =>
        HttpResponse.json(makeGroup({ logoUrl: "/api/v1/realty-groups/x/logo/image" })),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUploadLogo(), {
      wrapper: makeWrapper(qc),
    });
    const file = new File([new Uint8Array([1, 2, 3])], "logo.png", { type: "image/png" });
    await act(async () => {
      await result.current.mutateAsync({ publicId: GROUP_ID, file });
    });
    await waitFor(() =>
      expect(result.current.data?.logoUrl).toBe(
        "/api/v1/realty-groups/x/logo/image",
      ),
    );
  });

  it("useUploadCover succeeds on 200", async () => {
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/cover`, () =>
        HttpResponse.json(makeGroup()),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUploadCover(), {
      wrapper: makeWrapper(qc),
    });
    const file = new File([new Uint8Array([1, 2, 3])], "cover.jpg", { type: "image/jpeg" });
    await act(async () => {
      await result.current.mutateAsync({ publicId: GROUP_ID, file });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useRemoveMember invalidates on success", async () => {
    server.use(
      http.delete(
        `*/api/v1/realty-groups/${GROUP_ID}/members/${MEMBER_ID}`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
    const qc = newQc();
    const spy = vi.spyOn(qc, "invalidateQueries");
    const { result } = renderHook(() => useRemoveMember(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        memberPublicId: MEMBER_ID,
      });
    });
    const keys = spy.mock.calls.map(
      (c) => (c[0] as { queryKey?: unknown }).queryKey,
    );
    expect(keys).toContainEqual(realtyQueryKeys.all);
  });

  it("useUpdatePermissions returns the patched member card", async () => {
    server.use(
      http.patch(
        `*/api/v1/realty-groups/${GROUP_ID}/members/${MEMBER_ID}/permissions`,
        () => HttpResponse.json(makeAgent({ permissions: ["EDIT_GROUP_PROFILE"] })),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUpdatePermissions(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        memberPublicId: MEMBER_ID,
        body: { permissions: ["EDIT_GROUP_PROFILE"] },
      });
    });
    await waitFor(() =>
      expect(result.current.data?.permissions).toEqual([
        "EDIT_GROUP_PROFILE",
      ]),
    );
  });

  it("useLeaveGroup succeeds on 204", async () => {
    server.use(
      http.post(
        `*/api/v1/realty-groups/${GROUP_ID}/leave`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useLeaveGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(GROUP_ID);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useTransferLeadership returns the updated group", async () => {
    server.use(
      http.post(
        `*/api/v1/realty-groups/${GROUP_ID}/transfer-leadership`,
        () => HttpResponse.json(makeGroup()),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useTransferLeadership(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        body: {
          newLeaderPublicId: USER_ID,
          oldLeaderAction: "STAY",
        },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useInvite returns 201 with the invitation", async () => {
    server.use(
      http.post(
        `*/api/v1/realty-groups/${GROUP_ID}/invitations`,
        () => HttpResponse.json(makeInvitation(), { status: 201 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useInvite(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        body: { invitedUsername: "alice", permissions: [] },
      });
    });
    await waitFor(() => expect(result.current.data?.publicId).toBe(INV_ID));
  });

  it("useRevokeInvitation succeeds on 204", async () => {
    server.use(
      http.delete(
        `*/api/v1/realty-groups/${GROUP_ID}/invitations/${INV_ID}`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useRevokeInvitation(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        invitationPublicId: INV_ID,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useAcceptInvitation returns the joined group's summary card", async () => {
    server.use(
      http.post(`*/api/v1/me/invitations/${INV_ID}/accept`, () =>
        HttpResponse.json(makeSummary()),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAcceptInvitation(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(INV_ID);
    });
    await waitFor(() =>
      expect(result.current.data?.slug).toBe("mainland-realty"),
    );
  });

  it("useDeclineInvitation succeeds on 204", async () => {
    server.use(
      http.post(
        `*/api/v1/me/invitations/${INV_ID}/decline`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useDeclineInvitation(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(INV_ID);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useAdminUpdateGroup succeeds on 200", async () => {
    server.use(
      http.patch(`*/api/v1/admin/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(makeGroup()),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAdminUpdateGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        body: { description: "admin edit" },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useAdminDissolveGroup succeeds on 204", async () => {
    server.use(
      http.delete(
        `*/api/v1/admin/realty-groups/${GROUP_ID}`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAdminDissolveGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(GROUP_ID);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("useAdminRemoveMember passes newLeaderPublicId on the query string", async () => {
    let capturedUrl = "";
    server.use(
      http.delete(
        `*/api/v1/admin/realty-groups/${GROUP_ID}/members/${MEMBER_ID}`,
        ({ request }) => {
          capturedUrl = request.url;
          return new HttpResponse(null, { status: 204 });
        },
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAdminRemoveMember(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({
        publicId: GROUP_ID,
        memberPublicId: MEMBER_ID,
        newLeaderPublicId: USER_ID,
      });
    });
    expect(capturedUrl).toContain(`newLeaderPublicId=${USER_ID}`);
  });
});

// ─── Error mapping ─────────────────────────────────────────────────────────

describe("realty mutation hooks — error code mapping", () => {
  it("useCreateGroup maps GROUP_NAME_TAKEN to friendly copy", async () => {
    server.use(
      http.post("*/api/v1/realty-groups", () =>
        HttpResponse.json(problemBody(409, "GROUP_NAME_TAKEN"), { status: 409 }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useCreateGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync({ name: "taken" }).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useUpdateGroup maps GROUP_RENAME_COOLDOWN to friendly copy", async () => {
    server.use(
      http.patch(`*/api/v1/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(
          problemBody(409, "GROUP_RENAME_COOLDOWN", {
            cooldownEndsAt: "2026-06-01T00:00:00Z",
          }),
          { status: 409 },
        ),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUpdateGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current
        .mutateAsync({ publicId: GROUP_ID, body: { name: "renamed" } })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useInvite maps INVITATION_ALREADY_PENDING to friendly copy", async () => {
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, () =>
        HttpResponse.json(problemBody(409, "INVITATION_ALREADY_PENDING"), {
          status: 409,
        }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useInvite(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current
        .mutateAsync({
          publicId: GROUP_ID,
          body: { invitedUsername: "alice", permissions: [] },
        })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useInvite maps SEAT_LIMIT_REACHED to friendly copy", async () => {
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, () =>
        HttpResponse.json(problemBody(409, "SEAT_LIMIT_REACHED"), {
          status: 409,
        }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useInvite(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current
        .mutateAsync({
          publicId: GROUP_ID,
          body: { invitedUsername: "alice", permissions: [] },
        })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useLeaveGroup maps LEADER_CANNOT_LEAVE to friendly copy", async () => {
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/leave`, () =>
        HttpResponse.json(problemBody(409, "LEADER_CANNOT_LEAVE"), {
          status: 409,
        }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useLeaveGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(GROUP_ID).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useRemoveMember maps CANNOT_REMOVE_LEADER to friendly copy", async () => {
    server.use(
      http.delete(
        `*/api/v1/realty-groups/${GROUP_ID}/members/${MEMBER_ID}`,
        () =>
          HttpResponse.json(problemBody(409, "CANNOT_REMOVE_LEADER"), {
            status: 409,
          }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useRemoveMember(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current
        .mutateAsync({ publicId: GROUP_ID, memberPublicId: MEMBER_ID })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useTransferLeadership maps TRANSFER_TARGET_NOT_MEMBER to friendly copy", async () => {
    server.use(
      http.post(
        `*/api/v1/realty-groups/${GROUP_ID}/transfer-leadership`,
        () =>
          HttpResponse.json(
            problemBody(400, "TRANSFER_TARGET_NOT_MEMBER"),
            { status: 400 },
          ),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useTransferLeadership(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current
        .mutateAsync({
          publicId: GROUP_ID,
          body: { newLeaderPublicId: USER_ID, oldLeaderAction: "STAY" },
        })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useAcceptInvitation maps INVITATION_EXPIRED to friendly copy", async () => {
    server.use(
      http.post(`*/api/v1/me/invitations/${INV_ID}/accept`, () =>
        HttpResponse.json(problemBody(410, "INVITATION_EXPIRED"), {
          status: 410,
        }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAcceptInvitation(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(INV_ID).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useAcceptInvitation maps GROUP_DISSOLVED to friendly copy", async () => {
    server.use(
      http.post(`*/api/v1/me/invitations/${INV_ID}/accept`, () =>
        HttpResponse.json(problemBody(410, "GROUP_DISSOLVED"), {
          status: 410,
        }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useAcceptInvitation(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current.mutateAsync(INV_ID).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useUpdateGroup maps REALTY_GROUP_PERMISSION_DENIED to friendly copy", async () => {
    server.use(
      http.patch(`*/api/v1/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(
          problemBody(403, "REALTY_GROUP_PERMISSION_DENIED"),
          { status: 403 },
        ),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUpdateGroup(), {
      wrapper: makeWrapper(qc),
    });
    await act(async () => {
      await result.current
        .mutateAsync({ publicId: GROUP_ID, body: { description: "x" } })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("useUploadLogo maps UNSUPPORTED_IMAGE_FORMAT to friendly copy", async () => {
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/logo`, () =>
        HttpResponse.json(problemBody(415, "UNSUPPORTED_IMAGE_FORMAT"), {
          status: 415,
        }),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(() => useUploadLogo(), {
      wrapper: makeWrapper(qc),
    });
    const file = new File([new Uint8Array([1])], "x.gif", { type: "image/gif" });
    await act(async () => {
      await result.current
        .mutateAsync({ publicId: GROUP_ID, file })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
