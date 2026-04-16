# Deferred Work Ledger

Running list of work that was scoped out of a completed epic/sub-spec/task, deferred intentionally, or left partially implemented. Every entry must name:

1. **What** — the specific feature, endpoint, UI element, or behavior not delivered
2. **Where it came from** — the originating epic/sub-spec/task
3. **Why deferred** — blocker, phase dependency, or explicit out-of-scope decision
4. **When to complete** — target epic/phase, or "indefinite" if no target yet

**Read this file at the start of every brainstorming session for a new epic/sub-spec, alongside [CONVENTIONS.md](./CONVENTIONS.md).** When starting a new phase, sweep for items whose "when" matches — those are candidates to pull into the current work.

When finishing a sub-spec that completes a deferred item, remove the entry.

---

## Format

```markdown
### [short title]
- **From:** Epic NN sub-spec X / Task NN-NN
- **Why:** [reason]
- **When:** [target phase/epic, or "indefinite"]
- **Notes:** [any extra context, links to spec sections]
```

---

## Current Deferred Items

### LSL script for in-world verification terminal
- **From:** Epic 02 sub-spec 1 (Task 02-02)
- **Why:** Phase 11 is the dedicated LSL scripting phase. Sub-spec 1 shipped the backend `POST /api/v1/sl/verify` endpoint; the script that calls it is a separate work track.
- **When:** Phase 11
- **Notes:** Endpoint is testable via Postman `Dev/Simulate SL verify` helper today. The dev-profile `POST /api/v1/dev/sl/simulate-verify` stands in for the real LSL call during development.

### WebSocket push for verification completion
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard verify flow)
- **Why:** Considered during brainstorm. Deferred because the backend publisher needs to know when a real SL verification call succeeds — that signal source only exists once Phase 11 LSL work is real. Polling (5s, visibility-aware) is the right tool until then.
- **When:** Phase 11
- **Notes:** Replace the `useCurrentUser({ refetchInterval: 5000 })` polling with a STOMP subscription on `/topic/user/{userId}/verification`.

### Partial-star rendering for ReputationStars
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Phase 1 ships a simpler numeric "4.7 ★" display. Partial-star SVG rendering is polish that only matters when review counts are non-trivial.
- **When:** Epic 06 (Ratings & Reputation) when real review data exists
- **Notes:** Current `ReputationStars.tsx` at `frontend/src/components/user/ReputationStars.tsx`.

### Email change flow
- **From:** Epic 02 sub-spec 2b (Task 02-04 profile edit)
- **Why:** Requires a re-verification flow (new email → confirmation link → swap). Out of scope for the profile edit shipped in 2b.
- **When:** Epic 07 (user settings expansion)
- **Notes:** `ProfileEditForm` currently only covers `displayName` and `bio`.

### Account deletion UI
- **From:** Epic 02 sub-spec 2a (Task 02-03 user profile backend)
- **Why:** Backend `DELETE /me` returns 501 Not Implemented. Needs a GDPR-compliant deletion flow (cascade rules, data retention, soft-delete vs hard-delete decisions) that was out of scope for 2a.
- **When:** Future Epic 02 GDPR sub-spec, or Epic 07
- **Notes:** Dashboard has no delete button. Backend endpoint returns 501.

### Notification preferences editor
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard)
- **Why:** `CurrentUser.notifyEmail` and `notifySlIm` are returned by `/me` but no UI exposes them for editing. Editor design blocked on the notifications system coming online.
- **When:** Epic 07 (settings expansion) or Epic 09 (notifications)
- **Notes:** Shape of the JSON objects is defined — just needs a form.

### Realty group badge on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Task 02-05 mentions "Realty group badge (if applicable, Phase 2 — just leave space)." Phase 2 feature, group membership data source not defined.
- **When:** Phase 2 (post-MVP)
- **Notes:** `PublicProfileView` has no placeholder space reserved; add when requirements firm up.

### Follow/unfollow from public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Social features out of scope for Phase 1.
- **When:** Indefinite
- **Notes:** Not in the Phase 1 design document.

### Profile page SEO metadata (OpenGraph)
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Nice-to-have polish. Next.js 16 `generateMetadata` could emit OpenGraph tags for social sharing.
- **When:** Epic 07 or later
- **Notes:** Touchpoint is `frontend/src/app/users/[id]/page.tsx`.

### Drag-drop animation polish on ProfilePictureUploader
- **From:** Epic 02 sub-spec 2b (Task 02-04 profile picture upload)
- **Why:** Current drop zone uses a static border highlight. Polished version would animate border-color transition and a scale effect on drop.
- **When:** Indefinite (cosmetic)
- **Notes:** `frontend/src/components/user/ProfilePictureUploader.tsx`.

### Real data for My Bids tab
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard)
- **Why:** Tab skeleton ships with empty-state placeholder. Real data requires auction model from Epic 04.
- **When:** Epic 04 (Auction Engine)
- **Notes:** `frontend/src/app/dashboard/(verified)/bids/page.tsx` currently renders `<EmptyState>`.

### Real data for My Listings tab
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard)
- **Why:** Tab skeleton ships with empty-state placeholder. Real data requires listing model from Epic 03.
- **When:** Epic 03 (Parcel Management — listing creation sub-spec)
- **Notes:** `frontend/src/app/dashboard/(verified)/listings/page.tsx` currently renders `<EmptyState>`.

### Recent reviews section on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Review data requires the reviews model from Epic 06. Public profile ships with empty-state placeholder.
- **When:** Epic 06 (Ratings & Reputation)
- **Notes:** `PublicProfileView` renders `<EmptyState icon={MessageSquare}>` for this section.

### Active listings section on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Listing data requires auction/listing model from Epic 03/04. Public profile ships with empty-state placeholder.
- **When:** Epic 04 (Auction Engine)
- **Notes:** `PublicProfileView` renders `<EmptyState icon={Gavel}>` for this section.

---

## Removal Criteria

An entry is removed from this list when:
- The work lands in a merged PR, OR
- The decision is made to permanently not do the work (in which case, record that decision in a FOOTGUNS entry or elsewhere so the rationale survives)

Do not remove entries as a shortcut. If an item was "mostly done" but some specific piece is still missing, leave the entry and narrow its scope to what remains.
