"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { useAdminUpdateGroup } from "@/hooks/realty/useRealtyGroups";
import { cn } from "@/lib/cn";
import type { RealtyGroupPublicDto } from "@/types/realty";

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

export interface AdminGroupProfileFormProps {
  group: RealtyGroupPublicDto;
}

/**
 * Admin-edit profile form for `/admin/realty-groups/[publicId]`.
 *
 * Bypasses every member-side gate: an admin can rename a group regardless
 * of the 30-day cooldown, and edits do not advance the leader's next-rename
 * window (per backend {@code updateGroupAsAdmin} contract).
 *
 * Image upload is intentionally omitted here. Logo/cover are leader-side
 * concerns; admins forcing a logo swap should be a separate, rarer flow.
 */
export function AdminGroupProfileForm({ group }: AdminGroupProfileFormProps) {
  const updateGroup = useAdminUpdateGroup();

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
      // mutation hook surfaces a toast; swallow.
    }
  };

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Group profile</h2>
      </Card.Header>
      <Card.Body>
        <div
          className="mb-4 rounded-lg bg-info-bg px-3 py-2 text-xs text-info"
          data-testid="admin-edit-cooldown-banner"
        >
          Admin edits bypass the 30-day rename cooldown and do not bump the
          leader&apos;s next-rename window.
        </div>
        <form
          onSubmit={handleSubmit(onSubmit)}
          className="flex flex-col gap-5"
          aria-label="Admin group profile"
        >
          <Input
            label="Name"
            error={errors.name?.message}
            data-testid="admin-group-profile-name"
            {...register("name")}
          />

          <div className="flex flex-col gap-1">
            <label
              htmlFor="admin-group-profile-description"
              className="text-xs font-medium text-fg-muted"
            >
              Description
            </label>
            <textarea
              id="admin-group-profile-description"
              rows={4}
              className={cn(
                "w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-4 py-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-none",
              )}
              aria-invalid={errors.description ? true : undefined}
              data-testid="admin-group-profile-description"
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
            data-testid="admin-group-profile-website"
            {...register("website")}
          />

          <div className="flex justify-end">
            <Button
              type="submit"
              variant="primary"
              loading={isSubmitting || updateGroup.isPending}
              disabled={!isDirty || isSubmitting || updateGroup.isPending}
              data-testid="admin-group-profile-submit"
            >
              Save changes
            </Button>
          </div>
        </form>
      </Card.Body>
    </Card>
  );
}
