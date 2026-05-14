/* eslint-disable @next/next/no-img-element -- logo/cover bytes are API-served binary content */
"use client";

import { useCallback, useRef, type ChangeEvent } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import {
  useUpdateGroup,
  useUploadCover,
  useUploadLogo,
} from "@/hooks/realty/useRealtyGroups";
import { apiUrl } from "@/lib/api/url";
import { cn } from "@/lib/cn";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

const profileSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required")
    .max(64, "Name must be 64 characters or fewer"),
  description: z
    .string()
    .max(2000, "Description must be 2000 characters or fewer")
    .optional()
    .or(z.literal("")),
  website: z
    .string()
    .url("Enter a valid URL")
    .optional()
    .or(z.literal("")),
});

type ProfileFormValues = z.infer<typeof profileSchema>;

export interface GroupProfileFormProps {
  group: RealtyGroupPublicDto;
  /** Set of permissions the caller has on this group. Leader has all-implicit. */
  callerPermissions: Set<RealtyGroupPermission>;
  /** True when the caller is the leader; bypasses every disabled gate. */
  isLeader: boolean;
}

/**
 * Profile-tab form for `/dashboard/groups/[slug]/manage`. Edits:
 * name, description, website, and the logo/cover image pair, gated by
 * {@code EDIT_GROUP_PROFILE}. The leader has the permission implicitly.
 * Disabled inputs render with a tooltip ({@code title} attribute) so a
 * caller can see why a field is locked.
 */
export function GroupProfileForm({
  group,
  callerPermissions,
  isLeader,
}: GroupProfileFormProps) {
  const updateGroup = useUpdateGroup();
  const uploadLogo = useUploadLogo();
  const uploadCover = useUploadCover();

  const logoInputRef = useRef<HTMLInputElement>(null);
  const coverInputRef = useRef<HTMLInputElement>(null);

  const canEditProfile =
    isLeader || callerPermissions.has("EDIT_GROUP_PROFILE");

  const profileLockedTitle = canEditProfile
    ? undefined
    : "Requires the Edit group profile permission.";

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: {
      name: group.name,
      description: group.description ?? "",
      website: group.website ?? "",
    },
  });

  const onSubmit = async (values: ProfileFormValues) => {
    if (!canEditProfile) return;
    const body = {
      name: values.name?.trim() || undefined,
      description: values.description ? values.description.trim() : undefined,
      website: values.website ? values.website.trim() : undefined,
    };
    try {
      const updated = await updateGroup.mutateAsync({
        publicId: group.publicId,
        body,
      });
      reset({
        name: updated.name,
        description: updated.description ?? "",
        website: updated.website ?? "",
      });
    } catch {
      // Mutation hook handles the error toast; swallow rethrow here.
    }
  };

  const handlePickLogo = useCallback(
    async (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      e.target.value = "";
      if (!file) return;
      if (!ACCEPTED_TYPES.has(file.type)) return;
      uploadLogo.mutate({ publicId: group.publicId, file });
    },
    [uploadLogo, group.publicId],
  );

  const handlePickCover = useCallback(
    async (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      e.target.value = "";
      if (!file) return;
      if (!ACCEPTED_TYPES.has(file.type)) return;
      uploadCover.mutate({ publicId: group.publicId, file });
    },
    [uploadCover, group.publicId],
  );

  const logoUploadBusy = uploadLogo.isPending;
  const coverUploadBusy = uploadCover.isPending;

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Group profile</h2>
      </Card.Header>
      <Card.Body>
        <form
          onSubmit={handleSubmit(onSubmit)}
          className="flex flex-col gap-5"
          aria-label="Group profile"
        >
          {/* Logo + cover uploads */}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <label className="text-xs font-medium text-fg-muted">Logo</label>
              {group.logoUrl ? (
                <img
                  src={apiUrl(group.logoUrl) ?? undefined}
                  alt={`${group.name} logo`}
                  className="h-20 w-auto max-w-[12rem] rounded border border-border bg-surface-raised object-contain"
                  data-testid="group-profile-logo-preview"
                />
              ) : (
                <div className="inline-flex h-20 w-20 items-center justify-center rounded border border-border bg-info-bg text-xs font-semibold text-info">
                  No logo
                </div>
              )}
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => logoInputRef.current?.click()}
                disabled={!canEditProfile || logoUploadBusy}
                loading={logoUploadBusy}
                data-testid="group-profile-logo-button"
                title={profileLockedTitle}
              >
                {group.logoUrl ? "Replace logo" : "Upload logo"}
              </Button>
              <input
                ref={logoInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                className="hidden"
                aria-label="Upload group logo"
                onChange={handlePickLogo}
                data-testid="group-profile-logo-input"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-medium text-fg-muted">Cover</label>
              {group.coverUrl ? (
                <img
                  src={apiUrl(group.coverUrl) ?? undefined}
                  alt={`${group.name} cover`}
                  className="aspect-[16/5] w-full rounded border border-border bg-bg-hover object-contain"
                  data-testid="group-profile-cover-preview"
                />
              ) : (
                <div className="aspect-[16/5] w-full rounded border border-border bg-bg-hover" />
              )}
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => coverInputRef.current?.click()}
                disabled={!canEditProfile || coverUploadBusy}
                loading={coverUploadBusy}
                data-testid="group-profile-cover-button"
                title={profileLockedTitle}
              >
                {group.coverUrl ? "Replace cover" : "Upload cover"}
              </Button>
              <input
                ref={coverInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                className="hidden"
                aria-label="Upload group cover"
                onChange={handlePickCover}
                data-testid="group-profile-cover-input"
              />
            </div>
          </div>

          <Input
            label="Name"
            error={errors.name?.message}
            disabled={!canEditProfile}
            title={profileLockedTitle}
            data-testid="group-profile-name"
            {...register("name")}
          />

          <div className="flex flex-col gap-1">
            <label
              htmlFor="group-profile-description"
              className="text-xs font-medium text-fg-muted"
            >
              Description
            </label>
            <textarea
              id="group-profile-description"
              rows={4}
              disabled={!canEditProfile}
              title={profileLockedTitle}
              className={cn(
                "w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-4 py-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-none",
                !canEditProfile && "opacity-60 cursor-not-allowed",
              )}
              aria-invalid={errors.description ? true : undefined}
              data-testid="group-profile-description"
              {...register("description")}
            />
            {errors.description?.message && (
              <span className="text-xs text-danger">
                {errors.description.message}
              </span>
            )}
          </div>

          <Input
            label="Website"
            placeholder="https://example.com"
            error={errors.website?.message}
            disabled={!canEditProfile}
            title={profileLockedTitle}
            data-testid="group-profile-website"
            {...register("website")}
          />

          <div className="flex justify-end">
            <Button
              type="submit"
              variant="primary"
              loading={isSubmitting || updateGroup.isPending}
              disabled={
                !isDirty ||
                isSubmitting ||
                updateGroup.isPending ||
                !canEditProfile
              }
              data-testid="group-profile-submit"
            >
              Save changes
            </Button>
          </div>
        </form>
      </Card.Body>
    </Card>
  );
}
