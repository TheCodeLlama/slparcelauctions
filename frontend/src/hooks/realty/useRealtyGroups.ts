"use client";
import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { adminRealtyGroupsApi, realtyGroupsApi } from "@/lib/api/realtyGroups";
import { realtyGroupErrorMessage } from "@/lib/realty/errorMessages";
import { useToast } from "@/components/ui/Toast/useToast";
import type {
  AdminRealtyGroupsFilters,
  CreateInvitationRequest,
  CreateRealtyGroupRequest,
  TransferLeadershipRequest,
  UpdatePermissionsRequest,
  UpdateRealtyGroupRequest,
} from "@/types/realty";

// ─── Query keys ────────────────────────────────────────────────────────────

export const realtyQueryKeys = {
  all: ["realty"] as const,
  group: (publicId: string) =>
    [...realtyQueryKeys.all, "group", publicId] as const,
  groupBySlug: (slug: string) =>
    [...realtyQueryKeys.all, "group-by-slug", slug] as const,
  groupMembers: (publicId: string) =>
    [...realtyQueryKeys.group(publicId), "members"] as const,
  groupInvitations: (publicId: string) =>
    [...realtyQueryKeys.group(publicId), "invitations"] as const,
  myGroups: () => [...realtyQueryKeys.all, "me", "groups"] as const,
  myInvitations: () => [...realtyQueryKeys.all, "me", "invitations"] as const,
  userGroups: (userPublicId: string) =>
    [...realtyQueryKeys.all, "user", userPublicId, "groups"] as const,
  adminList: (filters: AdminRealtyGroupsFilters) =>
    [...realtyQueryKeys.all, "admin", "list", filters] as const,
  adminDetail: (publicId: string) =>
    [...realtyQueryKeys.all, "admin", "detail", publicId] as const,
};

// ─── Queries ───────────────────────────────────────────────────────────────

export function useRealtyGroup(publicId: string | undefined) {
  return useQuery({
    queryKey: realtyQueryKeys.group(publicId ?? ""),
    queryFn: () => realtyGroupsApi.getGroup(publicId!),
    enabled: !!publicId,
    staleTime: 5_000,
  });
}

export function useRealtyGroupBySlug(slug: string | undefined) {
  return useQuery({
    queryKey: realtyQueryKeys.groupBySlug(slug ?? ""),
    queryFn: () => realtyGroupsApi.getGroupBySlug(slug!),
    enabled: !!slug,
    staleTime: 5_000,
  });
}

export function useRealtyGroupMembers(publicId: string | undefined) {
  return useQuery({
    queryKey: realtyQueryKeys.groupMembers(publicId ?? ""),
    queryFn: () => realtyGroupsApi.listMembers(publicId!),
    enabled: !!publicId,
    staleTime: 5_000,
  });
}

export function useRealtyGroupInvitations(publicId: string | undefined) {
  return useQuery({
    queryKey: realtyQueryKeys.groupInvitations(publicId ?? ""),
    queryFn: () => realtyGroupsApi.listInvitations(publicId!),
    enabled: !!publicId,
    staleTime: 5_000,
  });
}

export function useMyRealtyGroups() {
  return useQuery({
    queryKey: realtyQueryKeys.myGroups(),
    queryFn: () => realtyGroupsApi.myGroups(),
    staleTime: 5_000,
  });
}

export function useMyInvitations() {
  return useQuery({
    queryKey: realtyQueryKeys.myInvitations(),
    queryFn: () => realtyGroupsApi.myInvitations(),
    staleTime: 5_000,
  });
}

export function useUserRealtyGroups(userPublicId: string | undefined) {
  return useQuery({
    queryKey: realtyQueryKeys.userGroups(userPublicId ?? ""),
    queryFn: () => realtyGroupsApi.userGroups(userPublicId!),
    enabled: !!userPublicId,
    staleTime: 5_000,
  });
}

export function useAdminRealtyGroupsList(filters: AdminRealtyGroupsFilters) {
  return useQuery({
    queryKey: realtyQueryKeys.adminList(filters),
    queryFn: () => adminRealtyGroupsApi.list(filters),
    staleTime: 5_000,
  });
}

export function useAdminRealtyGroup(publicId: string | undefined) {
  return useQuery({
    queryKey: realtyQueryKeys.adminDetail(publicId ?? ""),
    queryFn: () => adminRealtyGroupsApi.get(publicId!),
    enabled: !!publicId,
    staleTime: 5_000,
  });
}

// ─── Mutations: group CRUD ────────────────────────────────────────────────

/**
 * Wide invalidation: any group-touching mutation invalidates the entire
 * realty subtree. The realty surface area is small enough that the cost
 * of a few extra refetches is negligible compared to the bug surface of
 * tracking each affected key individually (e.g. group detail, member
 * list, "my groups" list, admin list — all need to stay coherent after
 * a leader transfer or rename).
 */
function invalidateAll(qc: ReturnType<typeof useQueryClient>) {
  qc.invalidateQueries({ queryKey: realtyQueryKeys.all });
}

export function useCreateGroup() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (body: CreateRealtyGroupRequest) =>
      realtyGroupsApi.createGroup(body),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Group created.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't create group.")),
  });
}

export function useUpdateGroup() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      body,
    }: {
      publicId: string;
      body: UpdateRealtyGroupRequest;
    }) => realtyGroupsApi.updateGroup(publicId, body),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Group updated.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't update group.")),
  });
}

export function useDissolveGroup() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (publicId: string) => realtyGroupsApi.dissolveGroup(publicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Group dissolved.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't dissolve group.")),
  });
}

export function useUploadLogo() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ publicId, file }: { publicId: string; file: File }) =>
      realtyGroupsApi.uploadLogo(publicId, file),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Logo updated.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't upload logo.")),
  });
}

export function useUploadCover() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ publicId, file }: { publicId: string; file: File }) =>
      realtyGroupsApi.uploadCover(publicId, file),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Cover updated.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't upload cover.")),
  });
}

// ─── Mutations: membership ────────────────────────────────────────────────

export function useRemoveMember() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      memberPublicId,
    }: {
      publicId: string;
      memberPublicId: string;
    }) => realtyGroupsApi.removeMember(publicId, memberPublicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Member removed.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't remove member.")),
  });
}

export function useUpdatePermissions() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      memberPublicId,
      body,
    }: {
      publicId: string;
      memberPublicId: string;
      body: UpdatePermissionsRequest;
    }) => realtyGroupsApi.updatePermissions(publicId, memberPublicId, body),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Permissions updated.");
    },
    onError: (err) =>
      toast.error(
        realtyGroupErrorMessage(err, "Couldn't update permissions."),
      ),
  });
}

export function useLeaveGroup() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (publicId: string) => realtyGroupsApi.leave(publicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("You left the group.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't leave the group.")),
  });
}

export function useTransferLeadership() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      body,
    }: {
      publicId: string;
      body: TransferLeadershipRequest;
    }) => realtyGroupsApi.transferLeadership(publicId, body),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Leadership transferred.");
    },
    onError: (err) =>
      toast.error(
        realtyGroupErrorMessage(err, "Couldn't transfer leadership."),
      ),
  });
}

// ─── Mutations: invitations (group-scoped) ────────────────────────────────

export function useInvite() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      body,
    }: {
      publicId: string;
      body: CreateInvitationRequest;
    }) => realtyGroupsApi.invite(publicId, body),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Invitation sent.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't send invitation.")),
  });
}

export function useRevokeInvitation() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      invitationPublicId,
    }: {
      publicId: string;
      invitationPublicId: string;
    }) => realtyGroupsApi.revokeInvitation(publicId, invitationPublicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Invitation revoked.");
    },
    onError: (err) =>
      toast.error(
        realtyGroupErrorMessage(err, "Couldn't revoke invitation."),
      ),
  });
}

// ─── Mutations: invitations (user-scoped) ─────────────────────────────────

export function useAcceptInvitation() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (invitationPublicId: string) =>
      realtyGroupsApi.acceptInvitation(invitationPublicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Invitation accepted.");
    },
    onError: (err) =>
      toast.error(
        realtyGroupErrorMessage(err, "Couldn't accept invitation."),
      ),
  });
}

export function useDeclineInvitation() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (invitationPublicId: string) =>
      realtyGroupsApi.declineInvitation(invitationPublicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Invitation declined.");
    },
    onError: (err) =>
      toast.error(
        realtyGroupErrorMessage(err, "Couldn't decline invitation."),
      ),
  });
}

// ─── Mutations: admin ─────────────────────────────────────────────────────

export function useAdminUpdateGroup() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      body,
    }: {
      publicId: string;
      body: UpdateRealtyGroupRequest;
    }) => adminRealtyGroupsApi.updateAsAdmin(publicId, body),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Group updated.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't update group.")),
  });
}

export function useAdminDissolveGroup() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (publicId: string) =>
      adminRealtyGroupsApi.dissolveAsAdmin(publicId),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Group dissolved.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't dissolve group.")),
  });
}

export function useAdminRemoveMember() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      publicId,
      memberPublicId,
      newLeaderPublicId,
    }: {
      publicId: string;
      memberPublicId: string;
      newLeaderPublicId?: string;
    }) =>
      adminRealtyGroupsApi.removeMemberAsAdmin(
        publicId,
        memberPublicId,
        newLeaderPublicId,
      ),
    onSuccess: () => {
      invalidateAll(qc);
      toast.success("Member removed.");
    },
    onError: (err) =>
      toast.error(realtyGroupErrorMessage(err, "Couldn't remove member.")),
  });
}
