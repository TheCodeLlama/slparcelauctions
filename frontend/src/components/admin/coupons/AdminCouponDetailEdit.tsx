"use client";
import { useEffect, useState, type FormEvent } from "react";
import { Trash2 } from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Checkbox } from "@/components/ui/Checkbox";
import { Input } from "@/components/ui/Input";
import { isApiError } from "@/lib/api";
import { usePatchAdminCoupon } from "@/hooks/admin/usePatchAdminCoupon";
import type { CouponDto, PatchCouponRequest } from "@/types/coupon";

type Props = {
  coupon: CouponDto;
  totalGrants: number;
};

/**
 * Coupon edit form. Decoupled from `AdminCouponForm` (the create
 * variant) because the patch semantics differ in three ways:
 *
 *  1. Identity fields (`code`, `discounts`, `allowedUserPublicIds`)
 *     are read-only here. Code is the natural key the wire never
 *     reassigns; the spec keeps the discount bundle frozen once a
 *     coupon is in flight to avoid surprise application changes.
 *  2. Lifetime and per-user knobs lock once any grant exists. The
 *     server enforces this via `IMMUTABLE_FIELD`; the form disables
 *     the inputs as a usability hint so the admin sees the
 *     restriction before submitting.
 *  3. Empty / cleared inputs become explicit `null` on the wire so
 *     the patch handler treats them as "clear" instead of "ignore".
 *
 * Re-using `AdminCouponForm` would have required threading mode +
 * pre-fill + per-field disable props through every section, doubling
 * the existing form's surface for a marginally cleaner factoring.
 * Keeping the two side-by-side is simpler and lets each evolve
 * independently.
 */

function toDatetimeLocalInputValue(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  // `datetime-local` wants `YYYY-MM-DDTHH:mm` in local time, no
  // seconds, no Z. Manual format to dodge the ISO toString quirks.
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

function describeError(err: unknown): string {
  if (!isApiError(err)) return "Could not save coupon. Try again.";
  const problem = err.problem as {
    code?: string;
    detail?: string;
    title?: string;
  };
  switch (problem.code) {
    case "IMMUTABLE_FIELD":
      return "One of the locked fields was edited. Refresh the page and try again.";
    case "SIGNUP_WINDOW_PAIRED":
      return "Signup window needs both a start and end date, or neither.";
  }
  return problem.detail ?? problem.title ?? "Could not save coupon.";
}

interface SectionProps {
  title: string;
  description?: string;
  children: React.ReactNode;
}

function Section({ title, description, children }: SectionProps) {
  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight text-fg">
          {title}
        </h2>
        {description && (
          <p className="mt-1 text-xs text-fg-muted">{description}</p>
        )}
      </Card.Header>
      <Card.Body>
        <div className="flex flex-col gap-4">{children}</div>
      </Card.Body>
    </Card>
  );
}

function LockedHint() {
  return (
    <p
      className="text-[11px] text-fg-muted"
      data-testid="locked-after-grant-hint"
    >
      Locked after first grant.
    </p>
  );
}

export function AdminCouponDetailEdit({ coupon, totalGrants }: Props) {
  const patchMutation = usePatchAdminCoupon(coupon.publicId);
  const locked = totalGrants > 0;

  // Pre-fill state from the loaded coupon.
  const [description, setDescription] = useState(coupon.description ?? "");
  const [active, setActive] = useState(coupon.active);
  const [notifyOnGrant, setNotifyOnGrant] = useState(coupon.notifyOnGrant);
  const [redeemableUntil, setRedeemableUntil] = useState(
    toDatetimeLocalInputValue(coupon.redeemableUntil),
  );
  const [maxTotalRedemptions, setMaxTotalRedemptions] = useState(
    coupon.maxTotalRedemptions === null
      ? ""
      : String(coupon.maxTotalRedemptions),
  );
  const [durationDays, setDurationDays] = useState(
    coupon.durationDays === null ? "" : String(coupon.durationDays),
  );
  const [useCount, setUseCount] = useState(
    coupon.useCount === null ? "" : String(coupon.useCount),
  );
  const [maxPerUser, setMaxPerUser] = useState(String(coupon.maxPerUser));
  const [signupStart, setSignupStart] = useState(
    coupon.signupWindowStart ?? "",
  );
  const [signupEnd, setSignupEnd] = useState(coupon.signupWindowEnd ?? "");

  const [allowlist, setAllowlist] = useState<string[]>(
    coupon.allowedUserPublicIds ?? [],
  );

  const [signupWindowError, setSignupWindowError] = useState<string | null>(
    null,
  );
  const [savedAt, setSavedAt] = useState<number | null>(null);

  // Re-sync pre-fill if the coupon ref shifts (e.g. patch-success
  // seeds the cache; the parent rerenders with the fresh DTO and we
  // don't want stale inputs). The coupon DTO is the external source
  // of truth and the form is its mirror; mirroring on prop change is
  // the point of this effect.
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- coupon prop is the external source of truth; mirroring it into local input state on prop change is the point of this effect. */
    setDescription(coupon.description ?? "");
    setActive(coupon.active);
    setNotifyOnGrant(coupon.notifyOnGrant);
    setRedeemableUntil(toDatetimeLocalInputValue(coupon.redeemableUntil));
    setMaxTotalRedemptions(
      coupon.maxTotalRedemptions === null
        ? ""
        : String(coupon.maxTotalRedemptions),
    );
    setDurationDays(
      coupon.durationDays === null ? "" : String(coupon.durationDays),
    );
    setUseCount(
      coupon.useCount === null ? "" : String(coupon.useCount),
    );
    setMaxPerUser(String(coupon.maxPerUser));
    setSignupStart(coupon.signupWindowStart ?? "");
    setSignupEnd(coupon.signupWindowEnd ?? "");
    setAllowlist(coupon.allowedUserPublicIds ?? []);
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [coupon]);

  function validate(): boolean {
    const startSet = signupStart.trim() !== "";
    const endSet = signupEnd.trim() !== "";
    if (startSet !== endSet) {
      setSignupWindowError(
        "Signup window needs both a start and end date, or neither.",
      );
      return false;
    }
    setSignupWindowError(null);
    return true;
  }

  function buildRequest(): PatchCouponRequest {
    const req: PatchCouponRequest = {};

    // Description: omit if unchanged, null if cleared, string otherwise.
    const trimmedDesc = description.trim();
    const origDesc = coupon.description ?? "";
    if (trimmedDesc !== origDesc) {
      req.description = trimmedDesc === "" ? null : trimmedDesc;
    }

    if (active !== coupon.active) req.active = active;
    if (notifyOnGrant !== coupon.notifyOnGrant) {
      req.notifyOnGrant = notifyOnGrant;
    }

    // redeemableUntil: compare ISO -> ISO. The input emits
    // `YYYY-MM-DDTHH:mm`; convert to ISO for the wire.
    const origRedeemableLocal = toDatetimeLocalInputValue(
      coupon.redeemableUntil,
    );
    if (redeemableUntil !== origRedeemableLocal) {
      if (redeemableUntil.trim() === "") {
        req.redeemableUntil = null;
      } else {
        const d = new Date(redeemableUntil);
        req.redeemableUntil = Number.isNaN(d.getTime())
          ? null
          : d.toISOString();
      }
    }

    const origMaxTotal =
      coupon.maxTotalRedemptions === null
        ? ""
        : String(coupon.maxTotalRedemptions);
    if (maxTotalRedemptions !== origMaxTotal) {
      req.maxTotalRedemptions =
        maxTotalRedemptions.trim() === ""
          ? null
          : Number(maxTotalRedemptions);
    }

    if (!locked) {
      const origDuration =
        coupon.durationDays === null ? "" : String(coupon.durationDays);
      if (durationDays !== origDuration) {
        const parsed = durationDays.trim() === "" ? NaN : Number(durationDays);
        if (!Number.isNaN(parsed)) req.durationDays = parsed;
      }
      const origUse =
        coupon.useCount === null ? "" : String(coupon.useCount);
      if (useCount !== origUse) {
        const parsed = useCount.trim() === "" ? NaN : Number(useCount);
        if (!Number.isNaN(parsed)) req.useCount = parsed;
      }
      if (maxPerUser !== String(coupon.maxPerUser)) {
        const parsed = maxPerUser.trim() === "" ? NaN : Number(maxPerUser);
        if (!Number.isNaN(parsed)) req.maxPerUser = parsed;
      }
    }

    // Allowlist: only include if changed (cheap shallow-equality).
    const origAllow = coupon.allowedUserPublicIds ?? [];
    const same =
      origAllow.length === allowlist.length &&
      origAllow.every((id, i) => id === allowlist[i]);
    if (!same) req.allowedUserPublicIds = allowlist;

    return req;
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!validate()) return;
    const req = buildRequest();
    if (Object.keys(req).length === 0) {
      // Nothing changed; surface a subtle "saved" pulse so the click
      // still feels responsive, then bail out.
      setSavedAt(Date.now());
      return;
    }
    patchMutation.mutate(req, {
      onSuccess: () => setSavedAt(Date.now()),
    });
  }

  function removeAllowlistEntry(id: string) {
    setAllowlist((prev) => prev.filter((p) => p !== id));
  }

  const serverError = patchMutation.isError
    ? describeError(patchMutation.error)
    : null;
  const submitting = patchMutation.isPending;

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-col gap-6 max-w-3xl"
      aria-label="Edit coupon"
      data-testid="coupon-edit-form"
      noValidate
    >
      <Section title="Identity (read-only)">
        <Input label="Code" value={coupon.code} disabled readOnly />
        <Input
          label="Description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="What this coupon is for"
          data-testid="edit-description-input"
        />
      </Section>

      <Section
        title="Lifetime"
        description={
          locked
            ? "Locked once any grant exists. Adjust these by archiving and creating a replacement coupon."
            : "Either duration, use count, or both."
        }
      >
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Duration (days)"
            type="number"
            min={1}
            value={durationDays}
            onChange={(e) => setDurationDays(e.target.value)}
            disabled={locked}
            data-testid="edit-duration-days-input"
          />
          <Input
            label="Use count"
            type="number"
            min={1}
            value={useCount}
            onChange={(e) => setUseCount(e.target.value)}
            disabled={locked}
            data-testid="edit-use-count-input"
          />
        </div>
        {locked && <LockedHint />}
      </Section>

      <Section title="Redemption controls">
        <Input
          label="Redeemable until (optional)"
          type="datetime-local"
          value={redeemableUntil}
          onChange={(e) => setRedeemableUntil(e.target.value)}
          data-testid="edit-redeemable-until-input"
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Max total redemptions (empty = unlimited)"
            type="number"
            min={1}
            value={maxTotalRedemptions}
            onChange={(e) => setMaxTotalRedemptions(e.target.value)}
            placeholder="unlimited"
            data-testid="edit-max-total-input"
          />
          <Input
            label="Max per user"
            type="number"
            min={1}
            value={maxPerUser}
            onChange={(e) => setMaxPerUser(e.target.value)}
            disabled={locked}
            data-testid="edit-max-per-user-input"
          />
        </div>
        {locked && <LockedHint />}

        {allowlist.length > 0 && (
          <div className="flex flex-col gap-2">
            <label className="text-xs font-medium text-fg-muted">
              Allowed users
            </label>
            <div
              className="flex flex-wrap gap-1.5"
              data-testid="edit-allowlist"
            >
              {allowlist.map((id) => (
                <span
                  key={id}
                  className="inline-flex items-center gap-1.5 rounded-full bg-info-bg px-2.5 py-1 text-[11px] text-info"
                  data-testid={`edit-allowlist-chip-${id}`}
                >
                  <span className="font-mono">
                    {id.length > 13 ? `${id.slice(0, 8)}...` : id}
                  </span>
                  <button
                    type="button"
                    onClick={() => removeAllowlistEntry(id)}
                    aria-label={`Remove allowed user ${id}`}
                    className="rounded-full p-0.5 hover:bg-info/20"
                  >
                    <Trash2 className="size-3" />
                  </button>
                </span>
              ))}
            </div>
            <p className="text-[11px] text-fg-muted">
              Remove an entry and save to drop them from the allowlist. Adding
              new entries is available from the Grants tab.
            </p>
          </div>
        )}
      </Section>

      <Section title="Auto-grant signup window">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Signup window start"
            type="date"
            value={signupStart}
            onChange={(e) => {
              setSignupStart(e.target.value);
              if (signupWindowError) setSignupWindowError(null);
            }}
            disabled={locked}
            data-testid="edit-signup-start-input"
          />
          <Input
            label="Signup window end"
            type="date"
            value={signupEnd}
            onChange={(e) => {
              setSignupEnd(e.target.value);
              if (signupWindowError) setSignupWindowError(null);
            }}
            disabled={locked}
            data-testid="edit-signup-end-input"
          />
        </div>
        {signupWindowError && (
          <p
            className="text-xs text-danger"
            data-testid="edit-signup-window-error"
          >
            {signupWindowError}
          </p>
        )}
        {locked && <LockedHint />}
      </Section>

      <Section title="Status">
        <Checkbox
          label="Active"
          checked={active}
          onChange={(e) => setActive(e.target.checked)}
          data-testid="edit-active-checkbox"
        />
        <Checkbox
          label="Notify recipients on grant"
          checked={notifyOnGrant}
          onChange={(e) => setNotifyOnGrant(e.target.checked)}
          data-testid="edit-notify-checkbox"
        />
      </Section>

      {serverError && (
        <div
          role="alert"
          data-testid="edit-form-error"
          className="rounded-lg border border-danger/30 bg-danger-bg/50 px-4 py-3 text-sm text-danger"
        >
          {serverError}
        </div>
      )}

      {savedAt !== null && !patchMutation.isError && (
        <div
          className="rounded-lg border border-success/30 bg-success-bg/50 px-4 py-3 text-sm text-success"
          data-testid="edit-form-saved"
        >
          Saved.
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button
          type="submit"
          variant="primary"
          loading={submitting}
          disabled={submitting}
          data-testid="edit-submit-btn"
        >
          Save changes
        </Button>
      </div>
    </form>
  );
}
