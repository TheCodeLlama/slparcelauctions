"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { useUpdateProfile } from "@/lib/user";
import type { CurrentUser } from "@/lib/user/api";

const profileSchema = z.object({
  displayName: z
    .string()
    .min(1, "Display name is required")
    .max(50, "Display name must be 50 characters or fewer")
    .regex(/^\S+(?:\s+\S+)*$/, "Display name cannot be blank or whitespace-only"),
  bio: z
    .string()
    .max(500, "Bio must be 500 characters or fewer")
    .optional()
    .or(z.literal("")),
});

type ProfileFormValues = z.infer<typeof profileSchema>;

type ProfileEditFormProps = {
  user: CurrentUser;
};

export function ProfileEditForm({ user }: ProfileEditFormProps) {
  const updateProfile = useUpdateProfile();

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: {
      displayName: user.displayName ?? "",
      bio: user.bio ?? "",
    },
  });

  const onSubmit = async (values: ProfileFormValues) => {
    const result = await updateProfile.mutateAsync({
      displayName: values.displayName,
      bio: values.bio || undefined,
    });
    reset({
      displayName: result.displayName ?? "",
      bio: result.bio ?? "",
    });
  };

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Edit Profile</h2>
      </Card.Header>
      <Card.Body>
        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-fg-muted">
              Email
            </label>
            <p className="text-sm text-fg">{user.email}</p>
          </div>

          <Input
            label="Display Name"
            error={errors.displayName?.message}
            {...register("displayName")}
          />

          <div className="flex flex-col gap-1">
            <label
              htmlFor="bio"
              className="text-xs font-medium text-fg-muted"
            >
              Bio
            </label>
            <textarea
              id="bio"
              rows={4}
              className="w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-4 py-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-none"
              aria-invalid={errors.bio ? true : undefined}
              {...register("bio")}
            />
            {errors.bio?.message && (
              <span className="text-xs text-danger">
                {errors.bio.message}
              </span>
            )}
          </div>

          <div className="flex justify-end">
            <Button
              type="submit"
              variant="primary"
              disabled={!isDirty || isSubmitting}
              loading={isSubmitting}
            >
              Save
            </Button>
          </div>
        </form>
      </Card.Body>
    </Card>
  );
}
