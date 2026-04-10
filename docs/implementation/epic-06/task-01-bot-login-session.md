# Task 06-01: Bot Login & Session Management

## Goal

Build the foundation of the C#/.NET bot service: logging in worker bot accounts via LibreMetaverse, maintaining sessions, and handling disconnections.

## Context

See DESIGN.md Section 5.4. This is a standalone C#/.NET project, separate from the Java backend. LibreMetaverse implements the SL viewer protocol. Worker bots are headless - no rendering, just protocol-level communication.

## What Needs to Happen

- Create a new .NET project (e.g., `bot/` directory alongside `backend/` and `frontend/`)
- Add LibreMetaverse NuGet dependency

- **Bot account configuration:**
  - Config file or environment variables for worker bot credentials (first name, last name, password)
  - Config for the primary escrow account UUID (SLPAEscrow - never logs in, just stored as reference)
  - Config for Java backend callback URL

- **Login manager:**
  - Login each configured worker bot on service startup
  - Handle login success/failure with retry (exponential backoff)
  - Maintain session keepalive (LibreMetaverse handles heartbeat, but watch for disconnects)
  - On disconnect: auto-reconnect with backoff
  - Track bot state: STARTING, ONLINE, RECONNECTING, OFFLINE, ERROR

- **Bot pool status:**
  - Track which bots are online and available
  - Track each bot's current region (updated on teleport)
  - Health check endpoint: GET /api/health - returns pool status (how many online, each bot's state)

- **Graceful shutdown:**
  - On service stop: logout all bots cleanly
  - Don't leave zombie sessions in SL

- **Logging:**
  - Log all login/logout/disconnect/reconnect events
  - Log errors with enough detail to diagnose SL-side issues

## Acceptance Criteria

- Service starts and logs in configured worker bots
- Bot sessions maintained (keepalive working)
- Disconnected bots auto-reconnect with backoff
- Health endpoint returns accurate pool status
- Service shuts down cleanly (all bots logged out)
- Login failures retried with exponential backoff
- Primary escrow account UUID stored in config, never logged in
- Bot state tracking accurate (ONLINE/OFFLINE/RECONNECTING)

## Notes

- Start with 2 worker bot accounts for development. Scaling is a config change.
- SL bot accounts are free to create. They should be marked as Scripted Agents per LL's bot policy (set in SL profile).
- LibreMetaverse's `GridClient` class is the main entry point. `GridClient.Network.Login()` handles auth.
- Don't worry about task execution yet - this task is purely about getting bots connected and staying connected.
