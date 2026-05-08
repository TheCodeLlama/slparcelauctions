"use client";
import { Input } from "@/components/ui/Input";
import {
  EditableSettingsModal,
  type EditableSettingsModalProps,
} from "./EditableSettingsModal";
import type { DraftSettings } from "./draftEditorMutations";

/**
 * Read-only preview of the buyer's BidPanel for the seller's DRAFT editor.
 * Shows the seller's actual settings (starting bid, buy now, reserve,
 * duration) but no fake current-bid / bidder-count — at activation the
 * listing starts empty.
 *
 * The bottom of the panel hosts the **Edit auction settings** trigger
 * which opens the settings modal. Settings are coupled (`buyNow > reserve
 * > startingBid`) so they save as a group.
 */
export interface BidPanelPreviewProps {
  settings: DraftSettings;
  onSettingsChange: EditableSettingsModalProps["onSave"];
}

export function BidPanelPreview({
  settings,
  onSettingsChange,
}: BidPanelPreviewProps) {
  const { startingBid, buyNowPrice, reservePrice, durationHours } = settings;

  return (
    <div
      data-testid="bid-panel-preview"
      className="flex flex-col gap-4 rounded-lg bg-surface-raised p-5 ring-1 ring-border-subtle"
    >
      <h3 className="text-sm font-semibold tracking-tight text-fg">
        Bid panel preview
      </h3>
      <dl className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between">
          <dt className="text-xs font-medium uppercase text-fg-muted">
            Starting bid
          </dt>
          <dd className="text-lg font-bold text-fg">
            L${startingBid.toLocaleString()}
          </dd>
        </div>
        {buyNowPrice != null && buyNowPrice > 0 && (
          <div className="flex items-baseline justify-between text-xs text-fg-muted">
            <dt>Buy it now</dt>
            <dd>L${buyNowPrice.toLocaleString()}</dd>
          </div>
        )}
        {reservePrice != null && reservePrice > 0 && (
          <div className="flex items-baseline justify-between text-xs text-fg-muted">
            <dt>Reserve</dt>
            <dd>Set</dd>
          </div>
        )}
        <div className="flex items-baseline justify-between text-xs text-fg-muted">
          <dt>Duration</dt>
          <dd>
            {durationHours % 24 === 0
              ? `${durationHours / 24} day${durationHours / 24 === 1 ? "" : "s"}`
              : `${durationHours} hours`}
          </dd>
        </div>
      </dl>
      <div className="flex flex-col gap-1">
        <Input
          type="number"
          value=""
          onChange={() => {}}
          disabled
          placeholder={`Min L$${(startingBid + 1).toLocaleString()}`}
          aria-label="Bid amount (preview)"
          data-testid="bid-panel-preview-input"
        />
        <p className="text-[11px] text-fg-muted">
          Listing not yet active.
        </p>
      </div>
      <div className="border-t border-border-subtle pt-3">
        <EditableSettingsModal
          value={settings}
          onSave={onSettingsChange}
          renderTrigger={(open) => (
            <button
              type="button"
              onClick={open}
              data-testid="bid-panel-preview-edit-settings"
              className="inline-flex items-center justify-center w-full h-9 px-4 rounded-sm bg-warning text-white text-sm font-medium hover:opacity-90 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-warning"
            >
              Edit auction settings
            </button>
          )}
        />
      </div>
    </div>
  );
}
