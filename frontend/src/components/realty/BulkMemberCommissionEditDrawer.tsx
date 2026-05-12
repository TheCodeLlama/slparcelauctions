"use client";

import { useMemo, useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Drawer } from "@/components/ui/Drawer";
import { useToast } from "@/components/ui/Toast/useToast";
import { useBulkCommissionEdit } from "@/hooks/realty/useBulkCommissionEdit";
import { isApiError } from "@/lib/api";
import type {
  AgentCardDto,
  BulkCommissionRateEntry,
  RealtyGroupPublicDto,
} from "@/types/realty";

export interface BulkMemberCommissionEditDrawerProps {
  /** Open flag — parent owns open state so the same drawer can be reused. */
  open: boolean;
  /** Group whose agent roster we're editing. Leader is excluded from the
   *  editable rows (only AGENT members have a commission rate). */
  group: RealtyGroupPublicDto;
  onClose: () => void;
}

/**
 * Leader-only bulk commission rate editor (Realty Groups: F §15.1).
 *
 * <p>Renders one row per AGENT member showing their avatar, display name,
 * current rate, and an editable percentage input. The single "Save all"
 * action sends the entire batch to {@code PATCH
 * /api/v1/realty-groups/{publicId}/members/commission-rates} which is
 * atomic — any single bad row rolls back the whole batch. Per spec, the
 * leader's row is not edited here (the leader has no agent-commission
 * rate).
 *
 * <p>Editable state is a percentage string ({@code "10"}, {@code "12.5"},
 * {@code ""}) so the user can type freely; we convert to a 0..1 decimal
 * string on submit (the wire format is {@code BigDecimal}-as-string).
 * Empty input is treated as zero — unlike the per-member edit form, the
 * bulk batch expects an explicit value for every row.
 *
 * <p>Errors:
 * <ul>
 *   <li>Client-side: a negative number, non-numeric, or {@code > 100}%
 *       blocks submit and surfaces an inline error on the offending row.</li>
 *   <li>Server-side: a {@code 400 MEMBER_NOT_IN_GROUP} (race against a
 *       concurrent removal) surfaces an inline error on the matching row.
 *       Other 4xx/5xx falls back to a generic toast.</li>
 * </ul>
 */
export function BulkMemberCommissionEditDrawer({
  open,
  group,
  onClose,
}: BulkMemberCommissionEditDrawerProps) {
  if (!open) return null;
  return (
    <Drawer open={open} onClose={onClose} title="Bulk edit commission rates">
      <BulkMemberCommissionEditForm
        key={group.publicId}
        group={group}
        onClose={onClose}
      />
    </Drawer>
  );
}

interface FormProps {
  group: RealtyGroupPublicDto;
  onClose: () => void;
}

/** Inline error per row, keyed by {@code memberPublicId}. */
type RowErrors = Record<string, string | undefined>;

function BulkMemberCommissionEditForm({ group, onClose }: FormProps) {
  const toast = useToast();
  const mutation = useBulkCommissionEdit(group.publicId);

  // Sort agents alphabetically for stable, scannable ordering.
  const sortedAgents = useMemo(
    () =>
      [...group.agents].sort((a, b) =>
        a.displayName.localeCompare(b.displayName, "en", {
          sensitivity: "base",
        }),
      ),
    [group.agents],
  );

  // Editable percentage strings keyed by memberPublicId. Initialised from
  // each agent's current rate. Null current rates become "" so the user
  // sees an empty input rather than "0" they'd have to clear.
  const [rates, setRates] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const agent of sortedAgents) {
      initial[agent.memberPublicId] =
        agent.agentCommissionRate != null
          ? (agent.agentCommissionRate * 100).toString()
          : "";
    }
    return initial;
  });

  const [rowErrors, setRowErrors] = useState<RowErrors>({});

  function setRate(memberPublicId: string, next: string) {
    setRates((prev) => ({ ...prev, [memberPublicId]: next }));
    // Clear that row's stale error as soon as the user edits it.
    setRowErrors((prev) => {
      if (prev[memberPublicId] === undefined) return prev;
      const next = { ...prev };
      delete next[memberPublicId];
      return next;
    });
  }

  /**
   * Validate every row. Returns the resolved {@link BulkCommissionRateEntry}
   * list when valid, or {@code null} after populating {@code rowErrors}.
   */
  function validate(): BulkCommissionRateEntry[] | null {
    const errors: RowErrors = {};
    const entries: BulkCommissionRateEntry[] = [];
    for (const agent of sortedAgents) {
      const raw = (rates[agent.memberPublicId] ?? "").trim();
      // Empty input is treated as zero — bulk batch needs an explicit rate
      // per row. The leader can still leave it blank to mean "0".
      const parsed = raw === "" ? 0 : Number(raw);
      if (!Number.isFinite(parsed)) {
        errors[agent.memberPublicId] = "Enter a number between 0 and 100.";
        continue;
      }
      if (parsed < 0) {
        errors[agent.memberPublicId] = "Rate must be 0 or greater.";
        continue;
      }
      if (parsed > 100) {
        errors[agent.memberPublicId] = "Rate must be 100 or lower.";
        continue;
      }
      // Convert percentage -> 0..1 decimal string for the wire.
      const decimal = (parsed / 100).toString();
      entries.push({
        memberPublicId: agent.memberPublicId,
        rate: decimal,
      });
    }
    if (Object.keys(errors).length > 0) {
      setRowErrors(errors);
      return null;
    }
    setRowErrors({});
    return entries;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const entries = validate();
    if (entries === null) return;
    try {
      await mutation.mutateAsync({ memberRates: entries });
      toast.success("Rates updated");
      onClose();
    } catch (err) {
      if (isApiError(err) && err.status === 400) {
        const problem = err.problem as {
          code?: string;
          detail?: string;
          memberPublicId?: string;
        };
        if (problem.code === "MEMBER_NOT_IN_GROUP") {
          // Backend's detail is "No member with publicId <uuid>." — try to
          // pull the offending publicId out so we can pin the error inline.
          const offender =
            problem.memberPublicId ??
            extractUuidFromDetail(problem.detail ?? "") ??
            null;
          if (offender && rates[offender] !== undefined) {
            setRowErrors({
              [offender]:
                "This member is no longer in the group. Refresh to continue.",
            });
            return;
          }
          // Couldn't pin to a row — show a top-level message.
          setRowErrors({
            __form: "One member is no longer in the group. Refresh and retry.",
          });
          return;
        }
        if (problem.code === "VALIDATION_FAILED" || problem.detail) {
          setRowErrors({
            __form: problem.detail ?? "One of the rates was rejected.",
          });
          return;
        }
      }
      toast.error("Couldn't save commission rates. Please try again.");
    }
  }

  const formError = rowErrors.__form;

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-col gap-4"
      aria-label="Bulk edit commission rates"
      data-testid="bulk-commission-form"
    >
      {sortedAgents.length === 0 ? (
        <p
          className="text-sm text-fg-muted"
          data-testid="bulk-commission-empty"
        >
          This group has no agents. Invite members first.
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {sortedAgents.map((agent) => (
            <BulkRow
              key={agent.memberPublicId}
              agent={agent}
              value={rates[agent.memberPublicId] ?? ""}
              onChange={(next) => setRate(agent.memberPublicId, next)}
              error={rowErrors[agent.memberPublicId]}
              disabled={mutation.isPending}
            />
          ))}
        </ul>
      )}

      {formError && (
        <div
          role="alert"
          data-testid="bulk-commission-form-error"
          className="rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger"
        >
          {formError}
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={onClose}
          disabled={mutation.isPending}
          data-testid="bulk-commission-cancel"
        >
          Cancel
        </Button>
        <Button
          type="submit"
          variant="primary"
          size="sm"
          loading={mutation.isPending}
          disabled={mutation.isPending || sortedAgents.length === 0}
          data-testid="bulk-commission-submit"
        >
          Save all
        </Button>
      </div>
    </form>
  );
}

interface BulkRowProps {
  agent: AgentCardDto;
  value: string;
  onChange: (next: string) => void;
  error: string | undefined;
  disabled: boolean;
}

/**
 * One row of the bulk editor. We inline a lightweight numeric input here
 * rather than reusing {@link CommissionRateInput} from sub-project E
 * because the latter is shaped for the single-member edit form (renders
 * its own label + helper text per instance, which would be visually noisy
 * stacked N times) and an inline per-row error message must sit
 * underneath. Re-using the same percentage-string contract keeps parity.
 */
function BulkRow({ agent, value, onChange, error, disabled }: BulkRowProps) {
  const currentRateLabel =
    agent.agentCommissionRate != null
      ? `${(agent.agentCommissionRate * 100).toFixed(2)}%`
      : "—";
  return (
    <li
      className="flex flex-col gap-1 rounded-lg border border-border bg-surface-raised px-3 py-2.5"
      data-testid={`bulk-commission-row-${agent.memberPublicId}`}
    >
      <div className="flex items-center gap-3">
        <Avatar
          src={agent.avatarUrl ?? undefined}
          alt={agent.displayName}
          name={agent.displayName}
          size="sm"
        />
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-sm font-medium text-fg">
            {agent.displayName}
          </span>
          <span
            className="text-[11px] text-fg-muted"
            data-testid={`bulk-commission-current-${agent.memberPublicId}`}
          >
            Current: {currentRateLabel}
          </span>
        </div>
        <div className="relative w-28 shrink-0">
          <input
            type="number"
            inputMode="decimal"
            min={0}
            max={100}
            step="0.01"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            disabled={disabled}
            aria-label={`New commission rate for ${agent.displayName}`}
            aria-invalid={error ? true : undefined}
            data-testid={`bulk-commission-input-${agent.memberPublicId}`}
            className="h-9 w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted pl-3 pr-7 text-sm ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand"
            placeholder="0"
          />
          <span
            className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-xs text-fg-muted"
            aria-hidden="true"
          >
            %
          </span>
        </div>
      </div>
      {error && (
        <p
          className="text-[11px] font-medium text-danger"
          data-testid={`bulk-commission-error-${agent.memberPublicId}`}
        >
          {error}
        </p>
      )}
    </li>
  );
}

/**
 * The backend's MEMBER_NOT_IN_GROUP problem detail reads
 * "No member with publicId <uuid>." We pull the UUID out so the inline
 * error can be pinned to the matching row.
 */
function extractUuidFromDetail(detail: string): string | null {
  const match = detail.match(
    /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i,
  );
  return match ? match[0] : null;
}
