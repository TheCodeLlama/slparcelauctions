"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createAuction,
  getAuction,
  updateAuction,
} from "@/lib/api/auctions";
import { deletePhoto, uploadPhoto } from "@/lib/api/auctionPhotos";
import {
  revokeStagedPhoto,
  type StagedPhoto,
} from "@/lib/listing/photoStaging";
import type {
  AuctionCreateRequest,
  AuctionDurationHours,
  AuctionPhotoDto,
  AuctionSnipeWindowMin,
  AuctionStatus,
  AuctionUpdateRequest,
  SellerAuctionResponse,
} from "@/types/auction";
import type { ParcelDto } from "@/types/parcel";

/**
 * Owns the form state for the Create/Edit listing wizard. The hook never
 * talks to the server outside of an explicit save() call — the Configure
 * step updates are in-memory only so the seller can edit freely without
 * stamping intermediate states to the backend.
 *
 * Photo lifecycle:
 *   - stagedPhotos: new files picked by the seller, not yet uploaded.
 *   - uploadedPhotos: full server-side photo DTOs (AuctionPhoto) the
 *     current auction already has. We keep the full DTO (not just ids)
 *     so the Review preview in edit mode can render the existing
 *     canonical URLs without needing a separate fetch.
 *   - removedPhotoIds: uploaded ids the seller removed since the last
 *     save; DELETE is queued and flushed on the next save().
 *
 * Persistence:
 *   - Edit mode (id supplied): sessionStorage keyed by "slpa:draft:<id>".
 *     Blob-backed stagedPhotos are intentionally dropped from the persisted
 *     snapshot (File/Blob can't round-trip JSON). Everything else — parcel,
 *     prices, tags, sellerDesc, auctionId, status, uploadedPhotos,
 *     removedPhotoIds — survives a tab close. Writes are debounced 150ms.
 *   - Create mode (no id): persistence is intentionally OFF. Visiting
 *     /listings/create always starts blank. Sellers who navigate away
 *     mid-form get a clean slate when they come back, which is the expected
 *     behavior for a creation flow (the alternate "draft recovery" was
 *     surprising — leaving the page mid-typing and returning still showed
 *     the old form). Once the first save promotes auctionId from null to a
 *     real id, the wizard navigates to /listings/<id>/activate and any
 *     subsequent edits land in edit mode where persistence is on.
 */
export interface DraftState {
  auctionId: number | null;
  status: AuctionStatus | null;
  /**
   * Seller-authored display title. `null` represents "seller hasn't typed
   * anything yet" — the wizard UI distinguishes empty strings (seller
   * cleared the field) from untouched, so keep the nullable slot rather
   * than collapsing to `""`. Submit-path validation enforces 1..120 chars
   * before {@link save} fires.
   */
  title: string | null;
  parcel: ParcelDto | null;
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  durationHours: AuctionDurationHours;
  snipeProtect: boolean;
  snipeWindowMin: AuctionSnipeWindowMin | null;
  sellerDesc: string;
  tags: string[];
  stagedPhotos: StagedPhoto[];
  uploadedPhotos: AuctionPhotoDto[];
  removedPhotoIds: number[];
  dirty: boolean;
}

const EMPTY: DraftState = {
  auctionId: null,
  status: null,
  title: null,
  parcel: null,
  startingBid: 100,
  reservePrice: null,
  buyNowPrice: null,
  durationHours: 72,
  snipeProtect: true,
  snipeWindowMin: 10,
  sellerDesc: "",
  tags: [],
  stagedPhotos: [],
  uploadedPhotos: [],
  removedPhotoIds: [],
  dirty: false,
};

/**
 * Fields that survive a JSON round-trip. Blob-backed stagedPhotos are
 * kept in memory only — recovering them would require re-picking files.
 */
type PersistedDraft = Omit<DraftState, "stagedPhotos">;

function storageKey(id: number | string | null): string {
  return `slpa:draft:${id ?? "new"}`;
}

function hydrateFromServer(a: SellerAuctionResponse): DraftState {
  return {
    auctionId: a.id,
    status: a.status,
    title: a.title,
    parcel: a.parcel,
    startingBid: a.startingBid,
    reservePrice: a.reservePrice,
    buyNowPrice: a.buyNowPrice,
    durationHours: a.durationHours as AuctionDurationHours,
    snipeProtect: a.snipeProtect,
    snipeWindowMin:
      (a.snipeWindowMin as AuctionSnipeWindowMin | null) ?? null,
    sellerDesc: a.sellerDesc ?? "",
    tags: a.tags.map((t) => t.code),
    stagedPhotos: [],
    uploadedPhotos: a.photos,
    removedPhotoIds: [],
    dirty: false,
  };
}

export interface UseListingDraftOptions {
  /** Existing auction id when editing an auction; undefined for create. */
  id?: number | string;
}

export interface UseListingDraftResult {
  state: DraftState;
  setParcel: (p: ParcelDto) => void;
  /**
   * Sets the listing title from the wizard's title input. Writes the raw
   * value through — trim and length-validation happen at submit time via
   * the wizard's Zod schema so a live char-counter can reflect what the
   * seller is typing (including trailing whitespace).
   */
  setTitle: (value: string) => void;
  update: <K extends keyof DraftState>(key: K, value: DraftState[K]) => void;
  addStagedPhotos: (next: StagedPhoto[]) => void;
  removeUploadedPhoto: (id: number) => void;
  save: () => Promise<SellerAuctionResponse>;
  isLoadingExisting: boolean;
}

/**
 * Lazy useState initializer. Reads the persisted draft out of
 * sessionStorage if one exists for this id (or "new" in create mode).
 * Runs once during the first render — React 19.2's
 * `react-hooks/set-state-in-effect` rule discourages the effect-based
 * alternative, and initializer-time hydration also avoids a wasted
 * initial render with EMPTY state.
 *
 * The `hydrated` flag is carried alongside the state so downstream
 * render-time checks never need to touch a ref (react-hooks/refs
 * forbids reading refs during render in React 19.2).
 */
interface DraftContainer {
  state: DraftState;
  hydrated: boolean;
}

function initialStateFrom(id: number | string | undefined): DraftContainer {
  if (typeof window === "undefined") return { state: EMPTY, hydrated: false };
  // Create mode: ignore any sessionStorage entry under "new" and start blank.
  // The persistence-on-write side (below) also skips writes in create mode,
  // so any stale "new" slot from before this gate landed is harmless and
  // simply orphaned.
  if (id === undefined || id === null) {
    return { state: EMPTY, hydrated: true };
  }
  try {
    const raw = window.sessionStorage.getItem(storageKey(id));
    if (!raw) return { state: EMPTY, hydrated: false };
    const parsed = JSON.parse(raw) as PersistedDraft;
    return { state: { ...parsed, stagedPhotos: [] }, hydrated: true };
  } catch {
    return { state: EMPTY, hydrated: false };
  }
}

export function useListingDraft(
  options: UseListingDraftOptions = {},
): UseListingDraftResult {
  const [container, setContainer] = useState<DraftContainer>(() =>
    initialStateFrom(options.id),
  );
  const { state, hydrated } = container;
  const qc = useQueryClient();

  const fetchQ = useQuery({
    queryKey: ["auction", options.id],
    // The edit flow is seller-only, so the server always returns a
    // SellerAuctionResponse here — narrow the public/seller union from the
    // shared {@code getAuction} (a verified non-seller on this route would
    // have been bounced by the route guard before hitting the query).
    queryFn: () => getAuction(options.id!) as Promise<SellerAuctionResponse>,
    // Only fetch when we're editing AND sessionStorage didn't already
    // hydrate us (no need to round-trip the server if we have a
    // preserved-across-tab-close draft).
    enabled:
      options.id !== undefined && options.id !== null && !hydrated,
    // The seller dashboard should always see canonical server state when
    // entering the edit flow — we don't want a stale React Query cache
    // masking a backend-side status change (e.g., the auction was
    // cancelled from another tab).
    staleTime: 0,
  });

  // Server hydration — only runs when sessionStorage had nothing for
  // this id. setState inside an effect is necessary because the data
  // arrives asynchronously from useQuery (no way to fold this into a
  // useState initializer). The explicit eslint-disable is the
  // documented escape hatch for this pattern (see ThemeToggle).
  useEffect(() => {
    if (hydrated) return;
    if (!fetchQ.data) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setContainer({
      state: hydrateFromServer(fetchQ.data),
      hydrated: true,
    });
  }, [fetchQ.data, hydrated]);

  const setState = useCallback(
    (updater: DraftState | ((s: DraftState) => DraftState)) => {
      setContainer((c) => ({
        ...c,
        state: typeof updater === "function" ? updater(c.state) : updater,
        hydrated: true,
      }));
    },
    [],
  );

  // Ref mirrors the latest state for the unmount flush below. The
  // debounced effect reads `state` from closure, but the unmount-only
  // effect has an empty dep list and needs a live reference so it can
  // flush the most recent snapshot.
  const pendingWriteRef = useRef<{
    state: DraftState;
    id: number | string | undefined;
    hydrated: boolean;
  } | null>(null);

  // Persist every state change (minus blob-backed staged files) so a
  // tab close doesn't nuke unsaved work in edit mode. Gated on `hydrated`
  // so we don't overwrite existing persisted state with the EMPTY placeholder
  // before server hydration runs. Create mode skips writes entirely — see
  // the persistence note in the file-level docblock. Writes are debounced
  // 150ms so a rapid keystroke burst turns into a single sessionStorage
  // write at the end.
  const isCreateMode =
    (options.id === undefined || options.id === null) && state.auctionId === null;
  useEffect(() => {
    if (typeof window === "undefined") return;
    if (!hydrated) return;
    if (isCreateMode) return;
    pendingWriteRef.current = { state, id: options.id, hydrated: true };
    const timerId = setTimeout(() => {
      const { stagedPhotos: _staged, ...persisted } = state;
      void _staged;
      const key = storageKey(state.auctionId ?? options.id ?? null);
      try {
        window.sessionStorage.setItem(key, JSON.stringify(persisted));
      } catch {
        // sessionStorage can throw in private-mode or when quota is hit.
        // The draft still works in-memory; we simply lose the tab-close
        // recovery guarantee.
      }
      pendingWriteRef.current = null;
    }, 150);
    return () => clearTimeout(timerId);
  }, [state, hydrated, options.id, isCreateMode]);

  // Unmount-only flush: if the component tears down while a debounced
  // write is still pending, write it out synchronously so tab-close
  // recovery still works (sub-spec 2 §4.1).
  useEffect(() => {
    return () => {
      if (typeof window === "undefined") return;
      const pending = pendingWriteRef.current;
      if (!pending || !pending.hydrated) return;
      const { stagedPhotos: _staged, ...persisted } = pending.state;
      void _staged;
      const key = storageKey(pending.state.auctionId ?? pending.id ?? null);
      try {
        window.sessionStorage.setItem(key, JSON.stringify(persisted));
      } catch {
        // See above — private-mode / quota.
      }
    };
  }, []);

  const update = useCallback(
    <K extends keyof DraftState>(key: K, value: DraftState[K]) => {
      setState((s) => ({ ...s, [key]: value, dirty: true }));
    },
    [setState],
  );

  const setParcel = useCallback(
    (p: ParcelDto) => {
      setState((s) => {
        // First-pick prefill: if the seller hasn't typed a title yet
        // (untouched OR cleared back to empty) and the parcel ships an
        // SL-side parcel name, seed the title from it. Once the seller
        // edits the title, we never touch it again — including on a
        // subsequent parcel swap. Edit mode flows through `hydrateFromServer`,
        // not setParcel, so this branch never fires there.
        const titleEmpty =
          s.title === null || s.title.trim().length === 0;
        const nextTitle =
          titleEmpty && p.parcelName ? p.parcelName : s.title;
        return { ...s, parcel: p, title: nextTitle, dirty: true };
      });
    },
    [setState],
  );

  const setTitle = useCallback(
    (value: string) => {
      update("title", value);
    },
    [update],
  );

  const addStagedPhotos = useCallback(
    (next: StagedPhoto[]) => {
      setState((s) => ({ ...s, stagedPhotos: next, dirty: true }));
    },
    [setState],
  );

  const removeUploadedPhoto = useCallback(
    (id: number) => {
      setState((s) => ({
        ...s,
        uploadedPhotos: s.uploadedPhotos.filter((p) => p.id !== id),
        removedPhotoIds: s.removedPhotoIds.includes(id)
          ? s.removedPhotoIds
          : [...s.removedPhotoIds, id],
        dirty: true,
      }));
    },
    [setState],
  );

  const save = useCallback(async (): Promise<SellerAuctionResponse> => {
    // Take a synchronous snapshot of current state. The hook re-creates
    // `save` whenever state changes, so the closed-over `state` is
    // always fresh at the moment the caller invoked it.
    const s = state;
    if (!s.parcel) {
      throw new Error("Cannot save without a parcel selected.");
    }
    // Title is required by the backend (sub-spec 1 Task 2). The wizard's
    // submit-path Zod validation blocks save() when the field is empty, so
    // reaching here with a null/blank title means either a non-wizard
    // caller or a bug. Send the trimmed value; surface the backend's
    // validation error verbatim if the server rejects it.
    const trimmedTitle = (s.title ?? "").trim();
    const createBody: AuctionCreateRequest = {
      parcelId: s.parcel.id,
      title: trimmedTitle,
      startingBid: s.startingBid,
      reservePrice: s.reservePrice,
      buyNowPrice: s.buyNowPrice,
      durationHours: s.durationHours,
      snipeProtect: s.snipeProtect,
      snipeWindowMin: s.snipeWindowMin,
      sellerDesc: s.sellerDesc,
      tags: s.tags,
    };

    const wasCreate = s.auctionId == null;
    let auction: SellerAuctionResponse;
    if (s.auctionId != null) {
      const { parcelId: _parcelId, ...updateBody } = createBody;
      void _parcelId;
      auction = await updateAuction(
        s.auctionId,
        updateBody as AuctionUpdateRequest,
      );
    } else {
      auction = await createAuction(createBody);
    }

    // First successful create: the draft now lives under its real id's
    // storage key (written by the persistence effect on the next render),
    // so purge the stale "new" slot to avoid leaking an orphan entry
    // that would hydrate a different tab into this same draft.
    if (wasCreate && typeof window !== "undefined") {
      try {
        window.sessionStorage.removeItem(storageKey(null));
      } catch {
        // Private-mode storage failure — nothing to clean up here.
      }
    }

    // Flush photo deletes queued from previous removeUploadedPhoto calls.
    await Promise.all(
      s.removedPhotoIds.map((id) => deletePhoto(auction.id, id)),
    );

    // Upload any staged photos. Errors are kept attached to their staged
    // entry so the UI can retry; successful ones are revoked (the server
    // URL replaces the blob URL once the refetch lands).
    const stagedAfter: StagedPhoto[] = [];
    for (const p of s.stagedPhotos) {
      if (p.error) {
        stagedAfter.push(p);
        continue;
      }
      try {
        await uploadPhoto(auction.id, p.file);
        revokeStagedPhoto(p);
      } catch (e: unknown) {
        stagedAfter.push({
          ...p,
          error:
            e instanceof Error && e.message
              ? e.message
              : "Upload failed.",
        });
      }
    }

    // Refetch to get the canonical server view (includes freshly uploaded
    // photos with real URLs + ids). The save flow is seller-only so the
    // response is always the seller shape — narrow the union accordingly.
    const refreshed = (await getAuction(auction.id)) as SellerAuctionResponse;
    qc.setQueryData(["auction", refreshed.id], refreshed);

    setState({
      ...hydrateFromServer(refreshed),
      // Preserve any staged photos that failed to upload so the seller
      // can retry without re-picking them.
      stagedPhotos: stagedAfter,
      dirty: stagedAfter.length > 0,
    });

    return refreshed;
  }, [qc, setState, state]);

  // isLoadingExisting stays true in edit mode until hydration has
  // actually populated the draft state — fetchQ resolving isn't enough,
  // because the hydrate useEffect runs one render later. `hydrated`
  // flips synchronously with the setContainer call inside that effect,
  // so consumers never see a render with draft.state still EMPTY after
  // we report !isLoadingExisting.
  const isLoadingExisting =
    options.id !== undefined && options.id !== null && !hydrated;

  return useMemo(
    () => ({
      state,
      setParcel,
      setTitle,
      update,
      addStagedPhotos,
      removeUploadedPhoto,
      save,
      isLoadingExisting,
    }),
    [
      state,
      setParcel,
      setTitle,
      update,
      addStagedPhotos,
      removeUploadedPhoto,
      save,
      isLoadingExisting,
    ],
  );
}
