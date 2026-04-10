# Task 03-01: World API & Map API Client

## Goal

Build a backend service client that fetches parcel and region data from Second Life's external APIs: the World API (parcel ownership/metadata) and the Map API (grid coordinates).

## Context

See DESIGN.md Sections 6.2, 6.3, 6.4. These APIs are used during parcel verification and listing creation to auto-populate parcel metadata and resolve grid coordinates.

## What Needs to Happen

- Create a World API client service:
  - GET `https://world.secondlife.com/place/{parcel_uuid}` - fetches HTML page with meta tags
  - Parse HTML meta tags: `og:title` (parcel name), `secondlife:region` (region name), plus hidden meta tags for `ownerid`, `ownertype` (agent/group), `area`, `description`, `snapshot`, `maturityrating`
  - Return a structured DTO with all parsed fields
  - Handle errors: 404 (parcel doesn't exist/deleted), timeouts, rate limiting
  - This is an unofficial API - add retry logic with backoff for transient failures

- Create a Map API client service:
  - Query `https://cap.secondlife.com/cap/0/b713fe80-283b-4585-af4d-a3b7d9a32492` to resolve region name → grid x/y coordinates
  - Return grid_x, grid_y for a given region name
  - Handle errors: unknown region, API unavailable

- Create a Grid Survey API client service (supplementary):
  - GET `https://api.gridsurvey.com/simquery.php?region={name}` - returns region info including estate type, creation date
  - Parse the `estate` field to determine if region is Mainland
  - Used to enforce Mainland-only restriction
  - Handle errors: region not found, API down (should not block verification if unavailable)

## Acceptance Criteria

- World API client correctly fetches and parses parcel metadata for a valid parcel UUID
- World API client returns appropriate error for invalid/deleted parcel UUIDs
- Map API client resolves a region name to grid coordinates
- Grid Survey client fetches region data and identifies estate type
- All clients handle timeouts and transient failures gracefully (retry with backoff)
- All clients have configurable timeouts and base URLs via application properties
- Unit tests with mocked HTTP responses cover success and error cases

## Notes

- The World API returns an HTML page, not JSON. You'll need an HTML parser (Jsoup or similar) to extract meta tag values.
- The Grid Survey API is third-party and may be slow. It should be optional - if it's down, verification can proceed without estate type validation (log a warning).
- The Map API CAP endpoint returns a simple text response with coordinates.
- These clients are consumed by the parcel verification service (Task 03-02) and listing creation (Task 03-04).
