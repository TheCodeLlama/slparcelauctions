"use client";
import { useState, useEffect } from "react";
import { useCreateBan } from "@/hooks/admin/useCreateBan";
import { Button } from "@/components/ui/Button";
import { UserSearchAutocomplete } from "./UserSearchAutocomplete";
import type { BanType, BanReasonCategory, AdminUserSummary } from "@/lib/admin/types";

const BAN_TYPES: BanType[] = ["IP", "AVATAR", "BOTH"];

const REASON_CATEGORIES: Array<{ value: BanReasonCategory; label: string }> = [
  { value: "SHILL_BIDDING", label: "Shill Bidding" },
  { value: "FRAUDULENT_SELLER", label: "Fraudulent Seller" },
  { value: "TOS_ABUSE", label: "ToS Abuse" },
  { value: "SPAM", label: "Spam" },
  { value: "OTHER", label: "Other" },
];

type Props = {
  open: boolean;
  onClose: () => void;
  initialSlAvatarUuid?: string;
  initialIpAddress?: string;
};

export function CreateBanModal({ open, onClose, initialSlAvatarUuid, initialIpAddress }: Props) {
  const [banType, setBanType] = useState<BanType>("IP");
  const [slAvatarUuid, setSlAvatarUuid] = useState(initialSlAvatarUuid ?? "");
  const [ipAddress, setIpAddress] = useState(initialIpAddress ?? "");
  const [expiresMode, setExpiresMode] = useState<"permanent" | "date">("permanent");
  const [expiresDate, setExpiresDate] = useState("");
  const [reasonCategory, setReasonCategory] = useState<BanReasonCategory>("OTHER");
  const [reasonText, setReasonText] = useState("");

  const createBan = useCreateBan();

  const hasPreFill = Boolean(initialSlAvatarUuid) || Boolean(initialIpAddress);

  useEffect(() => {
    if (!open) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- `open` is external source of truth; resetting form state on open is intentional
    setSlAvatarUuid(initialSlAvatarUuid ?? "");
    setIpAddress(initialIpAddress ?? "");
    setBanType(initialSlAvatarUuid && initialIpAddress ? "BOTH" : initialSlAvatarUuid ? "AVATAR" : "IP");
    setExpiresMode("permanent");
    setExpiresDate("");
    setReasonCategory("OTHER");
    setReasonText("");
  }, [open, initialSlAvatarUuid, initialIpAddress]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  function handleUserSelect(user: AdminUserSummary) {
    if (user.slAvatarUuid) setSlAvatarUuid(user.slAvatarUuid);
  }

  const canSubmit =
    reasonText.trim().length > 0 &&
    (banType === "IP" || banType === "BOTH" ? ipAddress.trim().length > 0 : true) &&
    (banType === "AVATAR" || banType === "BOTH" ? slAvatarUuid.trim().length > 0 : true) &&
    (expiresMode === "date" ? expiresDate.length > 0 : true) &&
    !createBan.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;

    createBan.mutate(
      {
        banType,
        ipAddress: banType === "AVATAR" ? null : ipAddress.trim() || null,
        slAvatarUuid: banType === "IP" ? null : slAvatarUuid.trim() || null,
        expiresAt:
          expiresMode === "permanent" ? null : new Date(expiresDate).toISOString(),
        reasonCategory,
        reasonText: reasonText.trim(),
      },
      { onSuccess: onClose }
    );
  }

  if (!open) return null;

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Create ban"
        data-testid="create-ban-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-lg rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4 max-h-[90vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-fg">Create ban</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
            >
              ✕
            </button>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {!hasPreFill && (
              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-fg">
                  Search user (optional)
                </label>
                <UserSearchAutocomplete onSelect={handleUserSelect} />
                <p className="text-[11px] text-fg-muted">
                  Selecting a user fills the avatar UUID below.
                </p>
              </div>
            )}

            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-fg">Ban type</label>
              <div className="flex items-center gap-2">
                {BAN_TYPES.map((t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => setBanType(t)}
                    data-testid={`ban-type-btn-${t}`}
                    className={`px-3 py-1.5 rounded-full text-[11px] font-medium transition-colors ${
                      banType === t
                        ? "bg-info-bg text-info font-medium"
                        : "bg-bg-muted text-fg-muted hover:bg-bg-hover"
                    }`}
                  >
                    {t}
                  </button>
                ))}
              </div>
            </div>

            {(banType === "IP" || banType === "BOTH") && (
              <div className="flex flex-col gap-1">
                <label htmlFor="ip-address" className="text-xs font-medium text-fg">
                  IP address <span className="text-danger">*</span>
                </label>
                <input
                  id="ip-address"
                  type="text"
                  value={ipAddress}
                  onChange={(e) => setIpAddress(e.target.value)}
                  placeholder="192.168.0.1"
                  data-testid="ip-address-input"
                  className="w-full rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand font-mono"
                />
              </div>
            )}

            {(banType === "AVATAR" || banType === "BOTH") && (
              <div className="flex flex-col gap-1">
                <label htmlFor="avatar-uuid" className="text-xs font-medium text-fg">
                  Avatar UUID <span className="text-danger">*</span>
                </label>
                <input
                  id="avatar-uuid"
                  type="text"
                  value={slAvatarUuid}
                  onChange={(e) => setSlAvatarUuid(e.target.value)}
                  placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                  data-testid="avatar-uuid-input"
                  className="w-full rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand font-mono"
                />
              </div>
            )}

            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-fg">Expiry</label>
              <div className="flex items-center gap-3">
                <label className="flex items-center gap-1.5 text-sm text-fg cursor-pointer">
                  <input
                    type="radio"
                    name="expiry-mode"
                    value="permanent"
                    checked={expiresMode === "permanent"}
                    onChange={() => setExpiresMode("permanent")}
                    data-testid="expiry-permanent"
                  />
                  Permanent
                </label>
                <label className="flex items-center gap-1.5 text-sm text-fg cursor-pointer">
                  <input
                    type="radio"
                    name="expiry-mode"
                    value="date"
                    checked={expiresMode === "date"}
                    onChange={() => setExpiresMode("date")}
                    data-testid="expiry-date-radio"
                  />
                  Until date
                </label>
              </div>
              {expiresMode === "date" && (
                <input
                  type="datetime-local"
                  value={expiresDate}
                  onChange={(e) => setExpiresDate(e.target.value)}
                  data-testid="expiry-date-input"
                  className="mt-1 rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
                />
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="reason-category" className="text-xs font-medium text-fg">
                Reason category
              </label>
              <select
                id="reason-category"
                value={reasonCategory}
                onChange={(e) => setReasonCategory(e.target.value as BanReasonCategory)}
                data-testid="reason-category-select"
                className="w-full rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
              >
                {REASON_CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="reason-text" className="text-xs font-medium text-fg">
                Reason details <span className="text-danger">*</span>
              </label>
              <textarea
                id="reason-text"
                rows={3}
                value={reasonText}
                disabled={createBan.isPending}
                onChange={(e) => setReasonText(e.target.value)}
                placeholder="Describe the reason for this ban…"
                data-testid="reason-text-textarea"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
            </div>

            <div className="rounded-lg bg-info-bg/30 border border-info-bg px-4 py-3 text-sm text-fg-muted">
              On create: token version bumps for any user with this avatar UUID, so their session
              invalidates on next refresh. Ban cache flushes immediately.
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="secondary" type="button" onClick={onClose} disabled={createBan.isPending}>
                Cancel
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={!canSubmit}
                loading={createBan.isPending}
                data-testid="create-ban-submit"
              >
                Create ban
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
