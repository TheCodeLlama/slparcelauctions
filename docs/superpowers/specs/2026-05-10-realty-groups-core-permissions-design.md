# Realty Groups — Core + Permissions Design

**Date:** 2026-05-10
**Author:** Heath Barcus
**Branch:** `feat/realty-groups-core-permissions`
**Scope:** Sub-projects **A** (Realty Group core) + **B** (Permissions model).
**Out of scope:** Sub-projects **C** (group-listing integration), **D** (group wallet), **E** (SL group correlation), **F** (admin moderation). Each tracked as a GitHub issue referencing this spec.

---

## 1. Goal

Implement Realty Groups: brokerage-style entities on SLParcels that let a leader manage a group of agents, where each `(group, agent)` pair carries a permission flag set gating administrative actions on that group. Each user can belong to many groups simultaneously, including leading multiple groups. C through F load on top of A+B and ship in separate cycles.

This realizes the long-standing design in `docs/initial-design/DESIGN.md` §4.4 with three intentional departures detailed in §10.

---

## 2. Architecture

The slice adds a new vertical-slice domain at `backend/src/main/java/com/slparcelauctions/backend/realty/`:

- Three entities (`RealtyGroup`, `RealtyGroupMember`, `RealtyGroupInvitation`) extending `BaseMutableEntity`.
- `RealtyGroupAuthorizer` for per-(user, group, permission) checks.
- `RealtyGroupService` orchestrating mutation flows.
- Four REST controllers: public reads, member self-service, user-side invitations, admin escape hatches.
- `RealtyGroupInvitationExpiryJob` (Spring `@Scheduled`) for invitation expiry.
- Flyway migration creating the three tables, partial unique indexes, and realizing the deferred FK on `auctions.realty_group_id`.

Frontend ships:
- Public profile page `/group/{slug}`.
- Member self-service under `/dashboard/groups/`.
- Admin pages under `/admin/realty-groups/`.

Cross-cutting fix: a central S3 upload helper that converts raster uploads to WebP at the chokepoint, consumed by avatars, listing photos, default covers, and the new logo+cover paths.

---

## 3. Data Model

All three entities extend `BaseMutableEntity` and inherit `id (Long, @JsonIgnore)`, `publicId (UUID)`, `createdAt`, `updatedAt`, `version`. Subclasses use `@SuperBuilder`, don't redeclare inherited fields, don't override `equals`/`hashCode`.

### 3.1 `realty_groups`

| Column | Type | Notes |
|---|---|---|
| inherited PK pair | BIGSERIAL `id` / UUID `public_id` | from `BaseMutableEntity` |
| `name` | VARCHAR(64) NOT NULL | Display name |
| `name_lower` | CITEXT GENERATED ALWAYS AS (lower(name)) STORED | Indexed `UNIQUE WHERE dissolved_at IS NULL` for case-insensitive uniqueness |
| `slug` | VARCHAR(80) NOT NULL | `UNIQUE WHERE dissolved_at IS NULL`; auto-derived on create + rename |
| `leader_id` | BIGINT NOT NULL FK `users(id)` | Single source of truth for leadership |
| `logo_object_key` | VARCHAR(500) NULL | S3 key, set by upload endpoint |
| `logo_content_type` | VARCHAR(100) NULL | Always `image/webp` after helper migration |
| `logo_size_bytes` | BIGINT NULL | |
| `cover_object_key` | VARCHAR(500) NULL | Mirrors User cover-image triple |
| `cover_content_type` | VARCHAR(100) NULL | |
| `cover_size_bytes` | BIGINT NULL | |
| `description` | TEXT NULL | |
| `website` | TEXT NULL | URL-validated or null |
| `agent_fee_rate` | DECIMAL(5,4) NOT NULL DEFAULT 0.0000 | Stored now, consumed by C |
| `agent_fee_split` | DECIMAL(5,4) NOT NULL DEFAULT 0.5000 | Stored now, consumed by C |
| `member_seat_limit` | INTEGER NOT NULL DEFAULT 50 | Future per-seat monetization placeholder |
| `last_renamed_at` | TIMESTAMPTZ NULL | NULL = never renamed; gates 30-day cooldown |
| `dissolved_at` | TIMESTAMPTZ NULL | Soft delete; group disappears from public/listings; name+slug reusable since unique indexes are partial on this column |
| inherited timestamps + version | | from `BaseMutableEntity` |

### 3.2 `realty_group_members`

| Column | Type | Notes |
|---|---|---|
| inherited PK pair | BIGSERIAL `id` / UUID `public_id` | |
| `group_id` | BIGINT NOT NULL FK `realty_groups(id)` ON DELETE CASCADE | |
| `user_id` | BIGINT NOT NULL FK `users(id)` | |
| `permissions` | TEXT[] NOT NULL DEFAULT '{}' | PostgreSQL string array; mapped to `Set<RealtyGroupPermission>` in JPA via `@JdbcTypeCode(SqlTypes.ARRAY)`; meaningless for leader (all-implicit) |
| `joined_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| inherited timestamps + version | | |

Constraints:
- `UNIQUE(group_id, user_id)` — free-form multi-group, one row per (group, user)
- Index on `user_id` for "my groups" queries

### 3.3 `realty_group_invitations`

| Column | Type | Notes |
|---|---|---|
| inherited PK pair | BIGSERIAL `id` / UUID `public_id` | |
| `group_id` | BIGINT NOT NULL FK `realty_groups(id)` ON DELETE CASCADE | |
| `invited_user_id` | BIGINT NOT NULL FK `users(id)` | |
| `invited_by_id` | BIGINT NOT NULL FK `users(id)` | Leader or member with `INVITE_AGENTS` at invite time |
| `status` | VARCHAR(10) NOT NULL DEFAULT 'PENDING' | enum-backed CHECK constraint over `PENDING\|ACCEPTED\|DECLINED\|REVOKED\|EXPIRED` |
| `permissions` | TEXT[] NOT NULL DEFAULT '{}' | Leader sets at invite time; copied to membership on accept |
| `expires_at` | TIMESTAMPTZ NOT NULL | 7 days from creation |
| `responded_at` | TIMESTAMPTZ NULL | Set when status transitions out of PENDING |
| inherited timestamps + version | | |

Constraints:
- Partial UNIQUE `(group_id, invited_user_id) WHERE status = 'PENDING'` — at most one live invitation per pair
- Index on `(invited_user_id, status)` for "my pending invitations" queries

### 3.4 `auctions` FK

The migration adds:

```sql
ALTER TABLE auctions
  ADD CONSTRAINT fk_auctions_realty_group
  FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id) ON DELETE SET NULL;
```

The column already exists and is null everywhere; no data migration. C consumes the column.

### 3.5 Lifecycle summary

| Flow | Mutation |
|---|---|
| Create | New `RealtyGroup` row + `RealtyGroupMember` row (creator as leader, empty permissions). Slug derived from name via the rule in §3.6. |
| Edit profile (non-admin) | UPDATE `realty_groups`; if name changed, set `last_renamed_at = NOW()` and recompute slug (§3.6). Rejected with `GROUP_RENAME_COOLDOWN` if `last_renamed_at + 30 days > NOW()`. |
| Edit profile (admin) | UPDATE `realty_groups`; if name changed, recompute slug but **do not** set `last_renamed_at` (so the leader isn't punished by an admin-initiated rename). Cooldown is computed off leader-driven renames only. |
| Upload logo/cover | Separate multipart endpoints; route through central S3 helper (Section 7) |
| Invite | INSERT `realty_group_invitations` (status=PENDING, expires_at=NOW()+7d, permissions=invite-time set). Validates: (a) invitee is not already a member, (b) `member_seat_limit` not yet reached, (c) no live duplicate (partial UNIQUE enforces this at DB layer). |
| Accept | Atomically (single tx): re-validate group is not dissolved (`GROUP_DISSOLVED` 410), invitee is still not a member (race vs. another accepted invitation; reject with `ALREADY_MEMBER` 409 in that case), seat limit still not reached (race vs. concurrent accepts; reject with `SEAT_LIMIT_REACHED` 409), then INSERT `realty_group_members` (permissions copied from invitation) and UPDATE invitation status to ACCEPTED. |
| Decline | UPDATE invitation status to DECLINED |
| Revoke | UPDATE invitation status to REVOKED |
| Expiry job | UPDATE … SET status=EXPIRED WHERE status=PENDING AND expires_at < NOW() RETURNING * |
| Edit permissions | UPDATE `realty_group_members.permissions` |
| Remove (non-admin) | DELETE `realty_group_members` row. Rejected with `CANNOT_REMOVE_LEADER` 409 if target is the leader. |
| Remove (admin) | DELETE `realty_group_members` row, including leader rows — but if target is the leader and the body specifies `newLeaderPublicId`, atomically transfer leadership to that member first (verify they're a current member, else 400). If target is the leader and no `newLeaderPublicId` provided, reject (admin should force-dissolve instead). |
| Leave | DELETE caller's `realty_group_members` row (rejected for leader with `LEADER_CANNOT_LEAVE` 409). |
| Transfer leadership | Single tx: verify `newLeaderPublicId` is a current member (else `TRANSFER_TARGET_NOT_MEMBER` 400). UPDATE `realty_groups.leader_id = new`. If `oldLeaderAction = STAY`, set old leader's `permissions` to all four flags. If `LEAVE`, delete old leader's member row. Notifications fire post-commit. |
| Dissolve | UPDATE `realty_groups SET dissolved_at = NOW()`; the partial unique indexes immediately allow name+slug reuse |

All mutations are `@Transactional`. Optimistic locking via inherited `@Version` handles concurrent permission edits.

### 3.6 Slug derivation rule

`slugOf(name)`:

1. Lowercase the name.
2. Replace any run of non-`[a-z0-9]` characters with a single `-`.
3. Trim leading/trailing `-`.
4. Truncate to 60 characters at a `-` boundary if possible.
5. If the result is empty (e.g. name was all non-ASCII), fall back to `group-<first 8 chars of publicId>`.
6. Check for collision against `realty_groups WHERE dissolved_at IS NULL`. If taken, append `-2`, `-3`, … until free. Cap at 80 chars total; if every suffix would exceed, truncate the base further.

Slug is recomputed on every rename via the same rule, then collision-resolved against the current non-dissolved set (excluding the row being renamed).

### 3.7 Membership invariants

- A group always has exactly one row in `realty_group_members` with `user_id = realty_groups.leader_id`. The service layer enforces this on every mutation that touches `leader_id`.
- The leader's member row exists for query convenience ("list all members"). Its `permissions` field is ignored by the authorizer (leader has all-implicit).
- Multi-group: `realty_group_members(group_id, user_id)` is unique, but `(user_id)` alone is not — a user may have N member rows across N groups, including being leader in some and agent in others.

---

## 4. Permissions Framework

### 4.1 Enum

```java
public enum RealtyGroupPermission {
    INVITE_AGENTS,
    REMOVE_AGENTS,
    EDIT_GROUP_PROFILE,
    CONFIGURE_FEES;
}
```

C/D/E/F append new values in their own PRs.

### 4.2 Authorizer service

`RealtyGroupAuthorizer` in `realty.auth`:

```java
boolean canDo(Long userId, Long groupId, RealtyGroupPermission p);
void assertCan(Long userId, Long groupId, RealtyGroupPermission p);  // throws RealtyGroupPermissionDeniedException (403)
boolean isMember(Long userId, Long groupId);
boolean isLeader(Long userId, Long groupId);
```

Resolution for `canDo` / `assertCan`:
1. Load `RealtyGroup` by id. If absent → throw `RealtyGroupNotFoundException` (404).
2. If `dissolved_at IS NOT NULL` → throw `GroupDissolvedException` (410). The check happens before membership lookup; even ex-members cannot mutate a dissolved group.
3. If `userId == group.leader_id` → return `true` (leader has all-implicit).
4. Load `RealtyGroupMember` by `(group_id, user_id)`; if absent → return `false`.
5. Otherwise → `member.permissions.contains(p)`.

`isMember` / `isLeader` follow the same dissolved-group rejection.

Plain DB read at each call; no caching. If the call path ever becomes hot, add a 5-second in-process cache later.

### 4.3 Defaults on invite

All four flags default OFF in the invitation builder; the leader explicitly picks at invite time. Invitee sees the proposed flag set in the accept UI before accepting.

### 4.4 Freshness

Each call re-reads from DB at the request boundary. Permission revocation takes effect on the next request — no WebSocket invalidation, no token version bump.

### 4.5 What's leader-only (not delegable)

- Transfer leadership
- Dissolve group
- Edit permissions on another member (delegating would let `INVITE_AGENTS` escalate via "invite + edit + leave loop")

---

## 5. API Surface

All endpoints under `/api/v1`. UUID `publicId` in every path; numeric `id` never crosses the wire (BaseEntity convention).

### 5.1 Group CRUD

| Method | Path | Auth/Gate |
|---|---|---|
| POST | `/realty-groups` | Authenticated. Creator becomes leader. |
| GET | `/realty-groups/{publicId}` | Public |
| GET | `/realty-groups/by-slug/{slug}` | Public — backs `/group/{slug}` page |
| PATCH | `/realty-groups/{publicId}` | `EDIT_GROUP_PROFILE`; rename gated on 30-day cooldown |
| POST | `/realty-groups/{publicId}/logo` (multipart) | `EDIT_GROUP_PROFILE`; goes through central S3 helper |
| POST | `/realty-groups/{publicId}/cover` (multipart) | `EDIT_GROUP_PROFILE`; same |
| DELETE | `/realty-groups/{publicId}` | Leader only; soft delete via `dissolved_at` |

### 5.2 Membership

| Method | Path | Auth/Gate |
|---|---|---|
| GET | `/realty-groups/{publicId}/members` | Public — avatar+name+role only; permissions+joined_at only if requester is a member or admin |
| DELETE | `/realty-groups/{publicId}/members/{memberPublicId}` | `REMOVE_AGENTS`; cannot target leader |
| PATCH | `/realty-groups/{publicId}/members/{memberPublicId}/permissions` | **Leader only** |
| POST | `/realty-groups/{publicId}/leave` | Caller-self; rejected for leader |
| POST | `/realty-groups/{publicId}/transfer-leadership` | Leader only; body `{ newLeaderPublicId, oldLeaderAction: "STAY"\|"LEAVE" }` |

### 5.3 Invitations (group-scoped)

| Method | Path | Auth/Gate |
|---|---|---|
| POST | `/realty-groups/{publicId}/invitations` | `INVITE_AGENTS`; body `{ invitedUsername, permissions: [...] }`; enforces `member_seat_limit` and "no live duplicate" |
| GET | `/realty-groups/{publicId}/invitations` | `INVITE_AGENTS` |
| DELETE | `/realty-groups/{publicId}/invitations/{invitationPublicId}` | `INVITE_AGENTS` |

### 5.4 Invitations (user-scoped)

| Method | Path | Auth/Gate |
|---|---|---|
| GET | `/me/invitations` | Authenticated; live PENDING invitations for caller |
| POST | `/me/invitations/{invitationPublicId}/accept` | Authenticated, invite must address caller; creates member row with invitation's flag set |
| POST | `/me/invitations/{invitationPublicId}/decline` | Same |

### 5.5 User profile read

| Method | Path | Auth/Gate |
|---|---|---|
| GET | `/users/{publicId}/realty-groups` | Public — feeds the "Groups" section on the user's public profile |
| GET | `/me/realty-groups` | Authenticated; same shape; dashboard convenience |

### 5.6 Admin

| Method | Path | Auth/Gate |
|---|---|---|
| GET | `/admin/realty-groups` | ROLE_ADMIN; paginated, filterable (status: active/dissolved, leader, search) |
| GET | `/admin/realty-groups/{publicId}` | ROLE_ADMIN; includes dissolved |
| PATCH | `/admin/realty-groups/{publicId}` | ROLE_ADMIN; bypasses rename cooldown |
| DELETE | `/admin/realty-groups/{publicId}` | ROLE_ADMIN; force-dissolve (no leader gate) |
| DELETE | `/admin/realty-groups/{publicId}/members/{memberPublicId}` | ROLE_ADMIN; can remove the leader, in which case the request body specifies a replacement `newLeaderPublicId` (must already be a current member) or the call is rejected. If the group has no other members, admin should force-dissolve instead. Optional query param `?newLeaderPublicId=...` carries the replacement when removing the leader; otherwise the body is empty. |

All five admin writes go through `AdminActionService` with new `AdminActionType` values: `REALTY_GROUP_EDIT`, `REALTY_GROUP_DISSOLVE`, `REALTY_GROUP_MEMBER_REMOVE`.

### 5.7 Domain exceptions

All `@ResponseStatus`-annotated and mapped via the slice's `@RestControllerAdvice`:

| Exception | Status | Code |
|---|---|---|
| `RealtyGroupNotFoundException` | 404 | `REALTY_GROUP_NOT_FOUND` |
| `RealtyGroupNameTakenException` | 409 | `GROUP_NAME_TAKEN` |
| `RealtyGroupRenameCooldownException` | 409 | `GROUP_RENAME_COOLDOWN` (body: `cooldownEndsAt`) |
| `MemberSeatLimitReachedException` | 409 | `SEAT_LIMIT_REACHED` |
| `InvitationAlreadyPendingException` | 409 | `INVITATION_ALREADY_PENDING` |
| `InvitationExpiredException` | 410 | `INVITATION_EXPIRED` |
| `InvitationNotFoundException` | 404 | `INVITATION_NOT_FOUND` |
| `LeaderCannotLeaveException` | 409 | `LEADER_CANNOT_LEAVE` |
| `CannotRemoveLeaderException` | 409 | `CANNOT_REMOVE_LEADER` |
| `LeaderTransferTargetNotMemberException` | 400 | `TRANSFER_TARGET_NOT_MEMBER` |
| `AlreadyMemberException` | 409 | `ALREADY_MEMBER` |
| `RealtyGroupPermissionDeniedException` | 403 | `REALTY_GROUP_PERMISSION_DENIED` (body: missing permission name) |
| `GroupDissolvedException` | 410 | `GROUP_DISSOLVED` |
| `InvalidWebsiteUrlException` | 400 | `INVALID_WEBSITE_URL` |
| `UnsupportedImageFormatException` (raised by helper) | 415 | `UNSUPPORTED_IMAGE_FORMAT` |

### 5.8 DTO shapes

```java
public record CreateRealtyGroupRequest(
    @NotBlank @Size(max=64) String name,
    @Size(max=2000) String description,
    @Size(max=500) String website
) {}

public record UpdateRealtyGroupRequest(
    @Size(max=64) String name,
    @Size(max=2000) String description,
    @Size(max=500) String website,
    @DecimalMin("0.0000") @DecimalMax("0.5000") BigDecimal agentFeeRate,
    @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal agentFeeSplit
) {}

public record RealtyGroupPublicDto(
    UUID publicId,
    String name, String slug,
    String description, String website,
    String logoUrl, String coverUrl,    // resolved via apiUrl()
    OffsetDateTime memberSince,
    LeaderCardDto leader,
    List<AgentCardDto> agents,          // public roster
    BigDecimal agentFeeRate, BigDecimal agentFeeSplit
) {}

public record RealtyGroupMemberDto(
    UUID memberPublicId,
    UUID userPublicId,
    String displayName,
    String avatarUrl,
    RealtyGroupRole role,                // computed: LEADER | AGENT
    Set<RealtyGroupPermission> permissions, // hidden from anonymous viewers
    OffsetDateTime joinedAt                  // hidden from anonymous viewers
) {}

public record CreateInvitationRequest(
    @NotBlank String invitedUsername,
    Set<RealtyGroupPermission> permissions
) {}

public record InvitationDto(
    UUID publicId,
    UUID groupPublicId, String groupName,
    UUID invitedByPublicId, String invitedByDisplayName,
    Set<RealtyGroupPermission> permissions,
    InvitationStatus status,
    OffsetDateTime expiresAt, OffsetDateTime createdAt
) {}

public record TransferLeadershipRequest(
    @NotNull UUID newLeaderPublicId,
    @NotNull OldLeaderAction oldLeaderAction  // STAY | LEAVE
) {}

public record UpdatePermissionsRequest(
    @NotNull Set<RealtyGroupPermission> permissions
) {}
```

---

## 6. Frontend Surface

### 6.1 Routes

| Path | Component shell | Render mode |
|---|---|---|
| `/group/[slug]` | `GroupPublicPage` | server component, `export const dynamic = "force-dynamic"` |
| `/dashboard/groups` | `GroupsListPage` | client, TanStack Query |
| `/dashboard/groups/create` | `GroupCreatePage` | client |
| `/dashboard/groups/[slug]/manage` | `GroupManagePage` (tabs) | client |
| `/dashboard/invitations` | `InvitationsPage` | client |
| `/admin/realty-groups` | `AdminGroupsListPage` | client |
| `/admin/realty-groups/[publicId]` | `AdminGroupDetailPage` | client |

### 6.2 Components (under `components/realty/`)

| Component | Purpose |
|---|---|
| `RealtyGroupHeroBanner` | Cover + logo overlay + name + description + member-since + website link |
| `LeaderCard` | Reused on public + manage pages |
| `RealtyGroupAgentsGrid` + `RealtyGroupMemberCard` | Public roster |
| `GroupCreateForm` | React Hook Form + Zod |
| `GroupProfileForm` | Profile tab; rename cooldown indicator inline |
| `MembersTab` | Members table with row actions gated by caller's flags |
| `InvitationsTab` | Pending + history |
| `SettingsTab` | Leader-only: transfer, dissolve, fees |
| `InvitationsList` | Used on `/dashboard/invitations` |
| `PermissionToggleRow` | Reused in invite-form + member edit |
| `GroupChip` / `GroupBadge` | Small primitive for "Listed by X of Group" (C reuses) |

### 6.3 User profile touchpoint

The existing user public profile page gets a "Groups" section that calls `GET /users/{publicId}/realty-groups` and renders a chip list with role badges. Hidden if empty.

### 6.4 State + API client

- `frontend/src/hooks/realty/useRealtyGroups.ts` — bundle of TanStack Query hooks
- `frontend/src/lib/api/realtyGroups.ts` — fetch wrappers
- `realtyGroupErrorMessage(err, fallback)` helper mapping codes to user copy (mirrors `adminListingErrorMessage`)

### 6.5 Caching

Public group pages send `Cache-Control: public, max-age=60` (mirrors featured rails posture). Admin pages are no-cache.

### 6.6 SSR safety

`/group/[slug]` is `force-dynamic` to avoid Amplify build-time prerender coupling. Any image URL (`logoUrl`, `coverUrl`) is rendered via `apiUrl(...)`. Backend image endpoints stay `permitAll` so the browser's image fetcher (no JWT) can load them.

---

## 7. Central S3 Upload Helper / WebP Conversion

A cross-cutting fix bundled into this slice. The user has identified the chokepoint as the right place to enforce WebP conversion so every existing caller benefits.

### 7.1 Audit step

Implementation begins by reading the existing image upload code (`AvatarImageProcessor`, `AvatarService`, listing-photo upload, `UserDefaultCoverService`) to determine whether a chokepoint helper exists or callers do direct `MinioClient.putObject`.

### 7.2 Target shape

A single `ImageStorageService`:

```java
public interface ImageStorageService {
    StoredImage storeImage(InputStream in, ImageStorageContext ctx);
}

public record ImageStorageContext(
    ImagePurpose purpose,    // AVATAR | LOGO | COVER | LISTING_PHOTO | DEFAULT_COVER
    int maxDim,
    String callerKey         // for object-key derivation; e.g. user publicId or group publicId
) {}

public record StoredImage(String objectKey, String contentType, long sizeBytes) {}
```

### 7.3 Pipeline inside the helper

1. Buffer first ~16 bytes; detect content type from magic bytes (do not trust client header).
2. Allow-list raster types: `image/jpeg`, `image/png`, `image/webp`, `image/heic`. Anything else → `UnsupportedImageFormatException` (415).
3. Decode via `ImageIO` (HEIC via `imageio-heif` if/when present; otherwise rejected with a clear message).
4. Resize to `ctx.maxDim` if larger.
5. Encode to WebP via TwelveMonkeys `imageio-webp` — quality 85 for opaque images; lossless for transparent logos.
6. Compute object key with `.webp` extension; write to MinIO/S3.
7. Return `StoredImage` with `contentType = "image/webp"`.

### 7.4 Caller migration

Every existing upload site re-points to `ImageStorageService.storeImage(...)`:
- `AvatarService` (avatar uploads)
- `UserDefaultCoverService` (default cover uploads)
- Any listing-photo upload path that currently does its own put
- New realty group `logo` + `cover` endpoints

Historical S3 objects in non-WebP formats remain valid: each domain entity's `*_content_type` column is the source of truth at serve time, so existing rows continue to render correctly. The helper change only affects new uploads going forward.

### 7.5 Maxima

| Purpose | maxDim | Quality |
|---|---|---|
| Avatar | 256 | 85 |
| Logo | 512 (longest side, aspect preserved) | lossless if alpha |
| Cover | 1920 (width) | 85 |
| Listing photo | 2048 (longest side) | 85 |
| Default cover | 1920 (width) | 85 |

### 7.6 Dependency

Add `com.twelvemonkeys.imageio:imageio-webp` to `backend/pom.xml`.

### 7.7 Tests

- Round-trip a known PNG (opaque + alpha): assert WebP magic bytes in output and reasonable size delta.
- Reject text/plain payload (415).
- Reject SVG payload (415) — explicitly tested so future-us doesn't accidentally accept XML.
- Reject HEIC payload until decoder is added.
- Verify dimension constraints (resize down works, no upscale).

---

## 8. Lifecycle Events + Notifications

Reuses existing `realty_group` notification category (email default ON, SL IM default OFF, in-app feed always). All publisher methods land on the existing `NotificationPublisher`.

| Method | Fires from | Recipients |
|---|---|---|
| `realtyGroupInvitationSent(invitation)` | `POST .../invitations` | Invitee |
| `realtyGroupInvitationAccepted(invitation)` | `POST /me/invitations/{id}/accept` | Leader + members with `INVITE_AGENTS` |
| `realtyGroupInvitationDeclined(invitation)` | `POST /me/invitations/{id}/decline` | Same |
| `realtyGroupInvitationExpired(invitation)` | Expiry job | Same |
| `realtyGroupMemberRemoved(group, removedUser)` | `DELETE .../members/{id}` | Removed user |
| `realtyGroupMemberLeft(group, leftUser)` | `POST .../leave` | Leader + `INVITE_AGENTS` delegates |
| `realtyGroupLeadershipTransferred(group, oldLeader, newLeader, oldStayed)` | `POST .../transfer-leadership` | Old leader, new leader, all other members |
| `realtyGroupDissolved(group, formerMembers)` | `DELETE .../{id}` | All former members |
| `realtyGroupPermissionsChanged(group, member, added, removed)` | `PATCH .../members/{id}/permissions` | Affected member |

Body copy follows SLParcels transactional copy conventions (no em-dashes, professional voice, links resolved to absolute URLs).

### 8.1 Expiry job

`RealtyGroupInvitationExpiryJob` runs `@Scheduled(fixedDelayString = "PT1M")`. Single SQL:

```sql
UPDATE realty_group_invitations
   SET status = 'EXPIRED', responded_at = NOW()
 WHERE status = 'PENDING' AND expires_at < NOW()
 RETURNING *;
```

Each returned row triggers `realtyGroupInvitationExpired(invitation)`. Gated by `slpa.realty.invitation-expiry.enabled` (default `true`; integration tests set `false` to avoid races, consistent with the shared scheduler-disable pattern).

---

## 9. Cross-Cutting Integration

| Surface | Change |
|---|---|
| `User` entity | None — `realty_group` notification keys already wired |
| `Auction` entity | FK constraint `realty_group_id → realty_groups(id) ON DELETE SET NULL` realized in migration; no new columns |
| User public profile page | New "Groups" section + query call |
| Notification preferences UI | None — category already supported |
| Admin shell nav | New "Realty Groups" entry; new admin pages |
| Username search | Invitation flow needs `GET /api/v1/users/search?q=`; audit first, ship here if absent |
| S3 upload helper | Central WebP-conversion fix (Section 7) |
| Postman | New "Realty Groups" folder mirroring API; admin endpoints under "Admin / Realty Groups"; threaded vars `groupPublicId`/`memberPublicId`/`invitationPublicId` |
| `AdminActionType` enum + DB CHECK | New entries: `REALTY_GROUP_EDIT`, `REALTY_GROUP_DISSOLVE`, `REALTY_GROUP_MEMBER_REMOVE` |
| Flyway migration | Single new migration creates 3 tables + indexes + FK + admin_action_type CHECK update |
| README.md | Sweep at task end per memory `feedback_update_readme_each_task` |

---

## 10. Intentional Departures from `docs/initial-design/DESIGN.md` §4.4

1. **Multi-group membership.** Original spec enforced one group per user (`UNIQUE(user_id)` on members). Updated: free-form, a user can belong to N groups simultaneously and lead multiple of them. Schema: `UNIQUE(group_id, user_id)`. Downstream UX in C gets a "List under which group?" picker on the auction-create flow.

2. **Permissions model.** Original spec: binary `LEADER`/`AGENT` role with fixed capability bundles. Updated: per-(group, agent) permission flag set; leader has all-implicit. The `role` column on `realty_group_members` is dropped because role is computed (`user_id == leader_id ? LEADER : AGENT`); the column would duplicate `realty_groups.leader_id` and risk drift.

3. **Rename cooldown.** Original spec had no rename cooldown. Updated: 30-day cooldown for non-admins, discouraging slug churn since we don't ship 301s or a slug-history table. Admin endpoint bypasses.

---

## 11. Testing Strategy

### 11.1 Backend

**Unit (Mockito):**
- `RealtyGroupAuthorizer` (leader-implicit, agent-flag, non-member, dissolved group)
- Slug generator (collision dedupe; case folding)
- Rename-cooldown calculator (within / outside window; admin bypass)
- Each `RealtyGroupService` mutation method in isolation
- Expiry job tick

**Slice (`@WebMvcTest`):**
- Every controller — auth gates, request validation, response shape
- Anonymous vs authenticated visibility of permissions+joined_at on `GET .../members`

**Integration (`@SpringBootTest` + real Postgres):**
- Full lifecycle: create → invite → accept → permission edit → remove → dissolve
- Multi-group: user is member of A and B; leads C
- Rename cooldown (within / outside / admin bypass)
- Case-insensitive name conflict
- Concurrent permission update races (optimistic locking via `@Version`)
- Soft-delete + name/slug reuse after dissolve
- Expiry job (seed past-expires invite; run job; assert EXPIRED + notification fired)
- Admin force-dissolve with active members
- Leader cannot leave; transfer-target-not-member rejection
- FK behavior: dissolving SET NULL-s `auctions.realty_group_id`
- Seat limit enforcement on invite

**Image helper:**
- Round-trip known PNG (opaque + alpha) → WebP magic bytes verified
- Reject text/plain (415)
- Reject SVG (415)
- Reject HEIC until decoder added
- Dimension constraint (resize down works; no upscale)

### 11.2 Frontend

- Vitest + RTL on every form and tab
- MSW handlers cover the new endpoints; success + 400/403/409/410 paths
- Integration: anonymous `/group/[slug]` render; member-with-`EDIT_GROUP_PROFILE` shows edit affordance; leader sees Settings tab
- `force-dynamic` posture asserted (no static prerender of group pages)
- `frontend/src/components/realty/**` — at minimum one test per primitive

### 11.3 Postman

- Every endpoint with variable-chained scripts (`groupPublicId`, `invitationPublicId`, `memberPublicId`)
- Negative cases (cooldown, seat-limit, already-pending) in a dedicated folder

### 11.4 Verify guards

- `npm run verify` in `frontend/` (no-dark-variants, no-hex-colors, no-inline-styles, coverage)
- Backend `./mvnw test` green

---

## 12. Out of Scope — Sub-projects C/D/E/F

Each ships as a separate spec → plan → implementation cycle. GitHub issues track the work and reference this spec.

### 12.1 C — Group-listing integration

- New permissions: `CREATE_LISTING`, `MANAGE_OWN_LISTING`, `MANAGE_ALL_LISTINGS`
- "List as: Individual / Group" picker on auction-create flow (multi-group → picker chooses which group)
- Listing card "Listed by X of Group" badge consuming `GroupChip` from A+B
- Agent-fee commission application at auction completion (consumes `agent_fee_rate`, `agent_fee_split` stored in A)
- Conflict-of-interest check on the bid path: can't bid on a listing created under a group you belong to
- Dissolution gate hardens to "no active listings"

### 12.2 D — Group wallet

- New permissions: `SPEND_FROM_GROUP_WALLET`, `WITHDRAW_FROM_GROUP_WALLET`, `VIEW_GROUP_TRANSACTIONS`
- Group wallet entity with `balance_lindens` + `reserved_lindens` mirroring the User wallet shape
- Group ledger (append-only per memory `feedback_ledgers_immutable`)
- Listing fee paid from group wallet (route from C's CREATE_LISTING path)
- Withdrawal flow (leader + delegates with `WITHDRAW_FROM_GROUP_WALLET`)
- Group dormancy mirror of user wallet dormancy
- Dissolution gate hardens to "+ no nonzero group wallet balance + no in-flight escrows"

### 12.3 E — SL group correlation

- `realty_group_sl_groups` table (per original DESIGN.md schema)
- SL group verification flow (about-text code OR founder via terminal)
- Method C wire-up: group-owned land becomes listable when the parcel's SL group UUID matches a verified `realty_group_sl_groups` row
- New permission: `REGISTER_SL_GROUP`

### 12.4 F — Admin moderation

- Group suspension (timed)
- Group fraud flagging
- Force-pause group's listings (cross-cuts with C)
- Group-level bans
- Reports against groups
- Audit-log surface dedicated to groups
- Group-level reputation/rating display (Phase 8 reviews integration)

---

## 13. Migration / Cutover

### 13.1 Flyway migration

Next sequential migration: `V24__realty_groups.sql` (or higher if another migration lands first).

```sql
-- Required PG extension (likely already present, but explicit)
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE realty_groups (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name VARCHAR(64) NOT NULL,
    name_lower CITEXT GENERATED ALWAYS AS (lower(name)) STORED,
    slug VARCHAR(80) NOT NULL,
    leader_id BIGINT NOT NULL REFERENCES users(id),
    logo_object_key VARCHAR(500),
    logo_content_type VARCHAR(100),
    logo_size_bytes BIGINT,
    cover_object_key VARCHAR(500),
    cover_content_type VARCHAR(100),
    cover_size_bytes BIGINT,
    description TEXT,
    website TEXT,
    agent_fee_rate NUMERIC(5,4) NOT NULL DEFAULT 0.0000,
    agent_fee_split NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    member_seat_limit INTEGER NOT NULL DEFAULT 50,
    last_renamed_at TIMESTAMPTZ,
    dissolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ix_realty_groups_name_lower_active
  ON realty_groups (name_lower) WHERE dissolved_at IS NULL;
CREATE UNIQUE INDEX ix_realty_groups_slug_active
  ON realty_groups (slug) WHERE dissolved_at IS NULL;
CREATE INDEX ix_realty_groups_leader ON realty_groups (leader_id);

CREATE TABLE realty_group_members (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id BIGINT NOT NULL REFERENCES realty_groups(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    permissions TEXT[] NOT NULL DEFAULT '{}',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (group_id, user_id)
);
CREATE INDEX ix_realty_group_members_user ON realty_group_members (user_id);

CREATE TABLE realty_group_invitations (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id BIGINT NOT NULL REFERENCES realty_groups(id) ON DELETE CASCADE,
    invited_user_id BIGINT NOT NULL REFERENCES users(id),
    invited_by_id BIGINT NOT NULL REFERENCES users(id),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
    permissions TEXT[] NOT NULL DEFAULT '{}',
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ix_invitations_one_live_per_pair
  ON realty_group_invitations (group_id, invited_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_invitee_status
  ON realty_group_invitations (invited_user_id, status);

ALTER TABLE auctions
  ADD CONSTRAINT fk_auctions_realty_group
  FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id) ON DELETE SET NULL;

-- Admin action enum widening (CHECK update handled in initializer, not migration,
-- per existing AdminActionTypeCheckConstraintInitializer pattern).
```

### 13.2 Backward compatibility

Zero. No existing data depends on any of the new tables. `auctions.realty_group_id` is null everywhere prior to A+B; the new FK doesn't reject any current rows.

### 13.3 Rollback

If catastrophic post-deploy: drop the FK, drop the three tables, revert the `AdminActionType` CHECK update. Frontend pages are additive — old pages don't reference the new endpoints.

### 13.4 Deferred image-pipeline sweep

If the audit in Section 7.1 reveals significant non-trivial migration work on existing upload sites (more than ~150 LoC across callers), the spec authorizes splitting the central S3 helper into a separate prerequisite PR landing first. The realty-groups slice then consumes the helper. The user retains final call.

---

## 14. Open / Deferred Decisions

None for A+B. C/D/E/F each have their own open questions handled in their respective brainstorm cycles.
