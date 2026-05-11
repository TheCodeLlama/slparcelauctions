"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { useUpdatePermissions } from "@/hooks/realty/useRealtyGroups";
import { ALL_PERMISSIONS } from "@/lib/realty/permissions";
import type { AgentCardDto, RealtyGroupPermission } from "@/types/realty";
import { PermissionToggleRow } from "./PermissionToggleRow";

export interface EditPermissionsFormProps {
  groupPublicId: string;
  member: AgentCardDto;
  onComplete?: () => void;
}

/**
 * Leader-only form rendered inside the members-tab "Edit permissions"
 * modal. Pre-populates from the member's current permission set, then
 * PATCHes the full flag set on submit.
 *
 * Member.permissions may be null when the caller cannot see the
 * fine-grained flag set (anonymous, non-member). The leader always sees
 * the full set so we treat null as empty defensively.
 */
export function EditPermissionsForm({
  groupPublicId,
  member,
  onComplete,
}: EditPermissionsFormProps) {
  const mutate = useUpdatePermissions();
  const [permissions, setPermissions] = useState<Set<RealtyGroupPermission>>(
    () => new Set(member.permissions ?? []),
  );

  useEffect(() => {
    setPermissions(new Set(member.permissions ?? []));
  }, [member]);

  function toggle(p: RealtyGroupPermission, on: boolean) {
    setPermissions((prev) => {
      const next = new Set(prev);
      if (on) next.add(p);
      else next.delete(p);
      return next;
    });
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      await mutate.mutateAsync({
        publicId: groupPublicId,
        memberPublicId: member.memberPublicId,
        body: { permissions: Array.from(permissions) },
      });
      onComplete?.();
    } catch {
      // toast handled by mutation onError
    }
  }

  return (
    <form
      onSubmit={onSubmit}
      className="flex flex-col gap-3"
      aria-label={`Edit permissions for ${member.displayName}`}
    >
      {ALL_PERMISSIONS.map((p) => (
        <PermissionToggleRow
          key={p}
          permission={p}
          checked={permissions.has(p)}
          onChange={(on) => toggle(p, on)}
        />
      ))}
      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          loading={mutate.isPending}
          disabled={mutate.isPending}
          data-testid="edit-permissions-submit"
        >
          Save permissions
        </Button>
      </div>
    </form>
  );
}
