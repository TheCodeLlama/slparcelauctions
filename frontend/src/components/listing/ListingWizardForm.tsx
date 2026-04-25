"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { isApiError } from "@/lib/api";
import { listParcelTagGroups } from "@/lib/api/parcelTags";
import { useListingDraft } from "@/hooks/useListingDraft";
import { cn } from "@/lib/cn";
import type { ParcelTagDto } from "@/types/parcelTag";
import type {
  AuctionPhotoDto,
  SellerAuctionResponse,
} from "@/types/auction";
import type { SuspensionReasonCode } from "@/types/cancellation";
import { AuctionSettingsForm, type AuctionSettingsValue } from "./AuctionSettingsForm";
import { ListingPreviewCard, type ListingPreviewAuction } from "./ListingPreviewCard";
import { ListingWizardLayout } from "./ListingWizardLayout";
import { ParcelLookupField } from "./ParcelLookupField";
import { PARCEL_TAGS_KEY, TagSelector } from "./TagSelector";
import { PhotoUploader } from "./PhotoUploader";
import { SuspensionErrorModal } from "./SuspensionErrorModal";

const SUSPENSION_CODES: ReadonlySet<SuspensionReasonCode> = new Set([
  "PENALTY_OWED",
  "TIMED_SUSPENSION",
  "PERMANENT_BAN",
]);

/**
 * Narrows a 403 ProblemDetail's {@code code} field to a known
 * {@link SuspensionReasonCode}. The backend's
 * {@code SellerSuspendedException} stamps one of three codes; any other
 * 403 (e.g. a plain auth failure) returns {@code null} so the wizard
 * falls back to its generic error path.
 */
function asSuspensionCode(value: unknown): SuspensionReasonCode | null {
  if (typeof value !== "string") return null;
  return SUSPENSION_CODES.has(value as SuspensionReasonCode)
    ? (value as SuspensionReasonCode)
    : null;
}

const WIZARD_STEPS = ["Configure", "Review & Submit"];
const MAX_DESC = 5000;
const MAX_TITLE = 120;
const TITLE_WARN_AT = 100;

/**
 * Submit-path Zod schema for the Listing Title field. Trims surrounding
 * whitespace before length-checking, so a title of spaces is rejected with
 * "Title is required" rather than silently hitting the 120-char cap.
 */
const titleSchema = z
  .string()
  .trim()
  .min(1, "Title is required")
  .max(MAX_TITLE, `Title must be ${MAX_TITLE} characters or less`);

export interface ListingWizardFormProps {
  mode: "create" | "edit";
  /** Auction id — required when mode='edit'. */
  id?: number | string;
}

/**
 * Shared form body for the Create (`/listings/create`) and Edit
 * (`/listings/[id]/edit`) flows. Both routes reduce to this component
 * with a different mode/id combo.
 *
 * Step 1 (Configure): parcel lookup + settings + description + tags +
 *   photos. In edit mode the parcel lookup is locked — the backend
 *   rejects parcel changes on a DRAFT_PAID auction (sub-spec 2 §6.2).
 * Step 2 (Review): read-only preview with Back/Submit footer.
 *
 * Save semantics:
 *   - "Save as Draft" / "Save changes" → draft.save() but stays on
 *     Configure; surfaces field errors inline through the form.
 *   - "Continue to Review" → saves and advances to step 2 on success.
 *   - "Submit" from Review → saves once more (to flush any in-step-2
 *     edits, which shouldn't happen but guards the happy path) and
 *     navigates to /listings/{id}/activate.
 */
export function ListingWizardForm({ mode, id }: ListingWizardFormProps) {
  const router = useRouter();
  const [step, setStep] = useState<"configure" | "review">("configure");
  const draft = useListingDraft({ id });

  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [titleError, setTitleError] = useState<string | null>(null);
  /**
   * Backend-driven suspension gate (Epic 08 sub-spec 2 §8.4). Set to a
   * {@link SuspensionReasonCode} when the wizard's save call returns a
   * 403 carrying the structured {@code code} field — drives the
   * focused {@link SuspensionErrorModal} instead of the inline
   * {@link FormError}. Cleared by the modal's dismiss handler.
   */
  const [suspensionCode, setSuspensionCode] =
    useState<SuspensionReasonCode | null>(null);

  const isEdit = mode === "edit";

  // Redirect edits of auctions whose status has progressed past
  // DRAFT_PAID to the activate page, per sub-spec 2 §4.4. We only
  // trigger once status is non-null (null means draft state hasn't
  // been hydrated from the server yet).
  useEffect(() => {
    if (!isEdit) return;
    const s = draft.state.status;
    if (s == null) return;
    if (s !== "DRAFT" && s !== "DRAFT_PAID") {
      router.replace(`/listings/${draft.state.auctionId}/activate`);
    }
  }, [isEdit, draft.state.status, draft.state.auctionId, router]);

  // Ref-guarded one-shot: after the first successful create, replace the
  // URL to /listings/{id}/edit so the seller lands on a durable URL that
  // survives a refresh. Tracked by ref (not state) to avoid re-renders
  // and to guarantee the redirect fires at most once per mount.
  const createRedirectedRef = useRef(false);

  // Used by the Review step to render tag chips by label (draft stores
  // codes; labels live in the tag catalogue fetch cached by TagSelector).
  const tagsQ = useQuery({
    queryKey: PARCEL_TAGS_KEY,
    queryFn: listParcelTagGroups,
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });

  const tagByCode = useMemo(() => {
    const map = new Map<string, ParcelTagDto>();
    for (const group of tagsQ.data ?? []) {
      for (const t of group.tags) map.set(t.code, t);
    }
    return map;
  }, [tagsQ.data]);

  const settings: AuctionSettingsValue = {
    startingBid: draft.state.startingBid,
    reservePrice: draft.state.reservePrice,
    buyNowPrice: draft.state.buyNowPrice,
    durationHours: draft.state.durationHours,
    snipeProtect: draft.state.snipeProtect,
    snipeWindowMin: draft.state.snipeWindowMin,
  };

  function applySettings(next: AuctionSettingsValue) {
    draft.update("startingBid", next.startingBid);
    draft.update("reservePrice", next.reservePrice);
    draft.update("buyNowPrice", next.buyNowPrice);
    draft.update("durationHours", next.durationHours);
    draft.update("snipeProtect", next.snipeProtect);
    draft.update("snipeWindowMin", next.snipeWindowMin);
  }

  async function runSave(): Promise<SellerAuctionResponse | null> {
    setError(null);
    // Title validation mirrors the backend's 1..120 char constraint
    // (sub-spec 1 Task 2 DTO). Surface the Zod message inline so the
    // seller sees what's wrong without a round-trip.
    const titleParse = titleSchema.safeParse(draft.state.title ?? "");
    if (!titleParse.success) {
      const first = titleParse.error.issues[0]?.message ?? "Invalid title.";
      setTitleError(first);
      return null;
    }
    setTitleError(null);
    const previousAuctionId = draft.state.auctionId;
    try {
      const saved = await draft.save();
      // First save in create mode — replace the URL so a refresh lands
      // back on the same auction's Configure step instead of a fresh
      // create flow (sub-spec 2 §4.1.4). The ref guard ensures we only
      // issue the replace once per create even if save() is called
      // again before navigation completes.
      if (
        !isEdit &&
        previousAuctionId == null &&
        saved.id != null &&
        !createRedirectedRef.current
      ) {
        createRedirectedRef.current = true;
        router.replace(`/listings/${saved.id}/edit`);
      }
      return saved;
    } catch (e: unknown) {
      // Listing-suspension gate (Epic 08 sub-spec 2 §8.4). Backend
      // emits 403 with a structured {@code code} field on
      // {@code SellerSuspendedException} — branch on the code rather
      // than the status alone so other 403s (e.g. auth scoping) still
      // surface the generic inline error. Suppress the inline copy
      // when we route to the focused modal so the seller doesn't see
      // both at once.
      if (isApiError(e) && e.status === 403) {
        const code = asSuspensionCode(e.problem.code);
        if (code) {
          setSuspensionCode(code);
          setError(null);
          return null;
        }
      }
      setError(
        isApiError(e)
          ? e.problem.detail ?? e.problem.title ?? "Save failed."
          : e instanceof Error
            ? e.message
            : "Save failed.",
      );
      return null;
    }
  }

  async function handleSave() {
    setSaving(true);
    try {
      await runSave();
    } finally {
      setSaving(false);
    }
  }

  async function handleContinue() {
    setSaving(true);
    try {
      const saved = await runSave();
      if (saved) setStep("review");
    } finally {
      setSaving(false);
    }
  }

  async function handleSubmit() {
    setSubmitting(true);
    try {
      const saved = await runSave();
      if (saved) router.push(`/listings/${saved.id}/activate`);
    } finally {
      setSubmitting(false);
    }
  }

  if (mode === "edit" && draft.isLoadingExisting) {
    return <LoadingSpinner label="Loading your listing..." />;
  }

  const parcel = draft.state.parcel;
  // "Save changes" only applies once the seller has paid the listing
  // fee — a still-DRAFT auction is semantically a draft. Create flow
  // starts at status=null → "Save as Draft". Sub-spec 2 §4.4.
  const saveLabel =
    draft.state.status === "DRAFT_PAID" ? "Save changes" : "Save as Draft";
  const title = isEdit ? "Edit listing" : "Create a listing";
  const description =
    isEdit
      ? "Update your listing details before paying the listing fee or relisting."
      : "Set up your parcel auction. You can save a draft and return to it any time.";

  // Mounted alongside both step branches so the focused suspension modal
  // (sub-spec 2 §8.4) appears regardless of whether the seller hit the
  // 403 from the Configure-step "Save" or the Review-step "Submit".
  const suspensionModal = (
    <SuspensionErrorModal
      code={suspensionCode}
      onClose={() => setSuspensionCode(null)}
    />
  );

  if (step === "configure") {
    return (
      <>
      {suspensionModal}
      <ListingWizardLayout
        steps={WIZARD_STEPS}
        currentIndex={0}
        title={title}
        description={description}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={handleSave}
              loading={saving}
              disabled={saving || submitting || !parcel}
            >
              {saveLabel}
            </Button>
            <Button
              onClick={handleContinue}
              loading={saving}
              disabled={saving || submitting || !parcel}
            >
              Continue to Review
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-6">
          <FormError message={error ?? undefined} />
          <section className="flex flex-col gap-2">
            <label
              htmlFor="listing-title"
              className="text-label-md font-semibold tracking-wider uppercase text-on-surface-variant"
            >
              Listing Title
            </label>
            <p className="text-body-sm text-on-surface-variant">
              A short, punchy headline for your listing (max 120 characters).
            </p>
            <TitleField
              value={draft.state.title ?? ""}
              onChange={(next) => {
                draft.setTitle(next);
                if (titleError) setTitleError(null);
              }}
              error={titleError}
            />
          </section>
          <section className="flex flex-col gap-2">
            <h2 className="text-title-md text-on-surface">Parcel</h2>
            <ParcelLookupField
              initialParcel={parcel}
              locked={isEdit}
              onResolved={draft.setParcel}
            />
          </section>
          {parcel && (
            <>
              <section className="flex flex-col gap-3">
                <h2 className="text-title-md text-on-surface">
                  Auction settings
                </h2>
                <AuctionSettingsForm
                  value={settings}
                  onChange={applySettings}
                />
              </section>

              <section className="flex flex-col gap-2">
                <h2 className="text-title-md text-on-surface">
                  Description
                </h2>
                <DescriptionField
                  value={draft.state.sellerDesc}
                  onChange={(next) => draft.update("sellerDesc", next)}
                />
              </section>

              <section className="flex flex-col gap-2">
                <h2 className="text-title-md text-on-surface">Tags</h2>
                <TagSelector
                  value={draft.state.tags}
                  onChange={(next) => draft.update("tags", next)}
                />
              </section>

              <section className="flex flex-col gap-2">
                <h2 className="text-title-md text-on-surface">Photos</h2>
                <PhotoUploader
                  staged={draft.state.stagedPhotos}
                  onStagedChange={draft.addStagedPhotos}
                />
              </section>
            </>
          )}
        </div>
      </ListingWizardLayout>
      </>
    );
  }

  // Review step — render a read-only preview.
  const previewAuction = parcel
    ? buildPreviewAuction({
        title: (draft.state.title ?? "").trim(),
        parcel,
        startingBid: draft.state.startingBid,
        reservePrice: draft.state.reservePrice,
        buyNowPrice: draft.state.buyNowPrice,
        durationHours: draft.state.durationHours,
        sellerDesc: draft.state.sellerDesc,
        tagCodes: draft.state.tags,
        tagByCode,
        stagedPhotos: draft.state.stagedPhotos.map((p) => p.objectUrl),
        uploadedPhotos: draft.state.uploadedPhotos,
      })
    : null;

  return (
    <>
      {suspensionModal}
      <ListingWizardLayout
        steps={WIZARD_STEPS}
        currentIndex={1}
        title="Review & Submit"
        description="Double-check your listing before continuing to activate."
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setStep("configure")}
              disabled={submitting}
            >
              Back to edit
            </Button>
            <Button
              onClick={handleSubmit}
              loading={submitting}
              disabled={submitting || !parcel}
            >
              Submit
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <FormError message={error ?? undefined} />
          {previewAuction && (
            <ListingPreviewCard auction={previewAuction} isPreview />
          )}
        </div>
      </ListingWizardLayout>
    </>
  );
}

/**
 * Title input with a live character counter. The counter renders muted by
 * default and switches to {@code text-error} once the seller crosses
 * {@link TITLE_WARN_AT} so they can see the cap approaching before they
 * hit it. Submit-path validation (1..120 chars after trim) lives on the
 * parent's {@link titleSchema}; this component only renders the raw
 * counter + optional inline error. Kept inline because the wizard is the
 * only consumer.
 */
function TitleField({
  value,
  onChange,
  error,
}: {
  value: string;
  onChange: (next: string) => void;
  error: string | null;
}) {
  const length = value.length;
  const warn = length >= TITLE_WARN_AT;
  // The visible <label htmlFor="listing-title"> lives on the parent
  // section (alongside the helper <p>); wiring through htmlFor means
  // getByLabelText still resolves to this input and screen readers pick
  // up a single accessible name.
  return (
    <div className="flex flex-col gap-1">
      <input
        id="listing-title"
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Give your listing a clear, compelling headline."
        aria-invalid={error != null}
        aria-describedby="listing-title-counter"
        className="w-full rounded-default bg-surface-container-low px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-transparent transition-all focus:bg-surface-container-lowest focus:outline-none focus:ring-primary"
      />
      <span
        id="listing-title-counter"
        className={cn(
          "self-end text-body-sm",
          warn ? "text-error" : "text-on-surface-variant",
        )}
        data-testid="title-counter"
      >
        {length} / {MAX_TITLE}
      </span>
      <FormError message={error ?? undefined} />
    </div>
  );
}

/**
 * Description textarea with a live character counter. Kept inline because
 * the Create/Edit form is the only consumer — if a second caller needs
 * the exact same widget, promote to components/ui/.
 */
function DescriptionField({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label
        htmlFor="listing-desc"
        className="sr-only"
      >
        Listing description
      </label>
      <textarea
        id="listing-desc"
        rows={5}
        maxLength={MAX_DESC}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Describe what makes this parcel special — location, views, build history, etc."
        className="w-full resize-y rounded-default bg-surface-container-low px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-transparent transition-all focus:bg-surface-container-lowest focus:outline-none focus:ring-primary"
      />
      <span
        className="self-end text-body-sm text-on-surface-variant"
        data-testid="desc-counter"
      >
        {value.length}/{MAX_DESC}
      </span>
    </div>
  );
}

/**
 * Builds a ListingPreviewAuction out of the draft state. Server-side
 * uploaded photos (full DTOs with canonical URLs) render first, followed
 * by any just-staged photos (object URLs with negative-id placeholders)
 * so the preview accurately reflects what a just-saved auction looks
 * like — critical in edit mode where the auction already has photos.
 */
function buildPreviewAuction({
  title,
  parcel,
  startingBid,
  reservePrice,
  buyNowPrice,
  durationHours,
  sellerDesc,
  tagCodes,
  tagByCode,
  stagedPhotos,
  uploadedPhotos,
}: {
  title: string;
  parcel: ListingPreviewAuction["parcel"];
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  durationHours: number;
  sellerDesc: string;
  tagCodes: string[];
  tagByCode: Map<string, ParcelTagDto>;
  stagedPhotos: string[];
  uploadedPhotos: AuctionPhotoDto[];
}): ListingPreviewAuction {
  const stagedEntries: AuctionPhotoDto[] = stagedPhotos.map((url, i) => ({
    id: -(i + 1),
    url,
    contentType: "image/jpeg",
    sizeBytes: 0,
    sortOrder: uploadedPhotos.length + i,
    uploadedAt: "",
  }));
  const photos: AuctionPhotoDto[] = [...uploadedPhotos, ...stagedEntries];

  const tags: ParcelTagDto[] = tagCodes.flatMap((code) => {
    const hit = tagByCode.get(code);
    return hit ? [hit] : [];
  });

  return {
    title,
    parcel,
    startingBid,
    reservePrice,
    buyNowPrice,
    durationHours,
    sellerDesc: sellerDesc || null,
    tags,
    photos,
  };
}
