"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
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
  AuctionSnipeWindowMin,
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
 *   - uploadedPhotoIds: server-side photo ids (AuctionPhoto.id) the
 *     current auction already has.
 *   - removedPhotoIds: uploaded ids the seller removed since the last
 *     save; DELETE is queued and flushed on the next save().
 *
 * Persistence:
 *   - sessionStorage keyed by "slpa:draft:<auctionId|new>". Blob-backed
 *     stagedPhotos are intentionally dropped from the persisted snapshot
 *     (File/Blob can't round-trip JSON). Everything else — parcel, prices,
 *     tags, sellerDesc, auctionId, uploadedPhotoIds, removedPhotoIds —
 *     survives a tab close.
 */
export interface DraftState {
  auctionId: number | null;
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
  uploadedPhotoIds: number[];
  removedPhotoIds: number[];
  dirty: boolean;
}

const EMPTY: DraftState = {
  auctionId: null,
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
  uploadedPhotoIds: [],
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
    uploadedPhotoIds: a.photos.map((p) => p.id),
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
  try {
    const raw = window.sessionStorage.getItem(storageKey(id ?? null));
    if (!raw) {
      // In create mode with nothing persisted, EMPTY is the canonical
      // starting point — mark hydrated so the server-fetch effect
      // doesn't wait for data that will never arrive.
      return { state: EMPTY, hydrated: id === undefined || id === null };
    }
    const parsed = JSON.parse(raw) as PersistedDraft;
    return { state: { ...parsed, stagedPhotos: [] }, hydrated: true };
  } catch {
    return { state: EMPTY, hydrated: id === undefined || id === null };
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
    queryFn: () => getAuction(options.id!),
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

  // Persist every state change (minus blob-backed staged files) so a
  // tab close doesn't nuke unsaved work. Gated on `hydrated` so we
  // don't overwrite existing persisted state with the EMPTY placeholder
  // before server hydration runs.
  useEffect(() => {
    if (typeof window === "undefined") return;
    if (!hydrated) return;
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
  }, [state, hydrated, options.id]);

  const update = useCallback(
    <K extends keyof DraftState>(key: K, value: DraftState[K]) => {
      setState((s) => ({ ...s, [key]: value, dirty: true }));
    },
    [setState],
  );

  const setParcel = useCallback(
    (p: ParcelDto) => {
      update("parcel", p);
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
        uploadedPhotoIds: s.uploadedPhotoIds.filter((x) => x !== id),
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
    const createBody: AuctionCreateRequest = {
      parcelId: s.parcel.id,
      startingBid: s.startingBid,
      reservePrice: s.reservePrice,
      buyNowPrice: s.buyNowPrice,
      durationHours: s.durationHours,
      snipeProtect: s.snipeProtect,
      snipeWindowMin: s.snipeWindowMin,
      sellerDesc: s.sellerDesc,
      tags: s.tags,
    };

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
    // photos with real URLs + ids).
    const refreshed = await getAuction(auction.id);
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
      update,
      addStagedPhotos,
      removeUploadedPhoto,
      save,
      isLoadingExisting,
    }),
    [
      state,
      setParcel,
      update,
      addStagedPhotos,
      removeUploadedPhoto,
      save,
      isLoadingExisting,
    ],
  );
}
