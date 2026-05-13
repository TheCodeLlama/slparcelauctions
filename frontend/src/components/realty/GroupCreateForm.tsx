"use client";

import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { useCreateGroup } from "@/hooks/realty/useRealtyGroups";

/**
 * Zod schema for creating a realty group. Mirrors backend validation:
 * - name: 1..64 chars
 * - description: 0..2000 chars, optional
 * - website: valid http(s) URL or empty; the empty string normalizes to
 *   undefined at submit so the backend stores null.
 */
const createGroupSchema = z.object({
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

type CreateGroupFormValues = z.infer<typeof createGroupSchema>;

/**
 * Form on `/groups/new`. Submits via {@link useCreateGroup}; on success,
 * navigates to the new group's profile page using the server-assigned
 * slug. Toast feedback is delegated to the mutation hook.
 */
export function GroupCreateForm() {
  const router = useRouter();
  const createGroup = useCreateGroup();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<CreateGroupFormValues>({
    resolver: zodResolver(createGroupSchema),
    defaultValues: { name: "", description: "", website: "" },
  });

  const onSubmit = async (values: CreateGroupFormValues) => {
    try {
      const created = await createGroup.mutateAsync({
        name: values.name.trim(),
        description: values.description ? values.description.trim() : undefined,
        website: values.website ? values.website.trim() : undefined,
      });
      router.push(
        `/groups/${encodeURIComponent(created.slug)}/profile`,
      );
    } catch {
      // Toast is dispatched by the mutation's onError handler; swallow the
      // rethrow here so React's unhandled-rejection surface stays quiet.
    }
  };

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">
          Create a realty group
        </h2>
      </Card.Header>
      <Card.Body>
        <form
          onSubmit={handleSubmit(onSubmit)}
          className="flex flex-col gap-4"
          aria-label="Create realty group"
        >
          <Input
            label="Name"
            placeholder="Mainland Realty"
            error={errors.name?.message}
            data-testid="group-create-name"
            {...register("name")}
          />
          <div className="flex flex-col gap-1">
            <label
              htmlFor="group-create-description"
              className="text-xs font-medium text-fg-muted"
            >
              Description
            </label>
            <textarea
              id="group-create-description"
              rows={4}
              className="w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-4 py-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-none"
              placeholder="Tell people what your group is about."
              aria-invalid={errors.description ? true : undefined}
              data-testid="group-create-description"
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
            data-testid="group-create-website"
            {...register("website")}
          />
          <div className="flex justify-end">
            <Button
              type="submit"
              variant="primary"
              loading={isSubmitting || createGroup.isPending}
              disabled={isSubmitting || createGroup.isPending}
              data-testid="group-create-submit"
            >
              Create group
            </Button>
          </div>
        </form>
      </Card.Body>
    </Card>
  );
}
