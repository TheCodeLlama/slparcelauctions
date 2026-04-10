# Task 06-03: Task Queue & Backend Communication

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the task queue that receives work from the Java backend, distributes it across available bots, and reports results back.

## Context

The Java backend needs to tell the bot service "verify this parcel" or "start monitoring this auction." The bot service picks up tasks, assigns them to available workers, and POSTs results back.

## What Needs to Happen

- **REST API for receiving tasks from Java backend:**
  - POST /api/tasks - create a new task
    - Task types: VERIFY, MONITOR_AUCTION, MONITOR_ESCROW
    - Payload: region_name, parcel_coordinates (x,y,z), parcel_uuid, expected_owner_uuid, expected_auth_buyer_uuid, auction_id, callback_url
    - For VERIFY: one-shot task, execute once and return result
    - For MONITOR_*: recurring task, execute on schedule until cancelled
  - DELETE /api/tasks/{id} - cancel a task (stop monitoring)
  - GET /api/tasks - list all active tasks with status

- **Task distribution:**
  - Assign tasks to available online bots
  - Simple round-robin or least-loaded assignment
  - If a bot goes offline, reassign its tasks to other workers
  - Group tasks by region to minimize teleports (multiple parcels in same region = one teleport)

- **Scheduling for recurring tasks:**
  - MONITOR_AUCTION: execute every 30 minutes
  - MONITOR_ESCROW: execute every 15 minutes
  - Track next_run_at for each recurring task
  - Process due tasks in order (oldest first)

- **Result callback to Java backend:**
  - After each task execution: POST result to the task's callback_url
  - Payload: task_id, task_type, success (boolean), parcel_data (full ParcelProperties fields), error_type (if failed), timestamp
  - Retry callback on failure (3 attempts with backoff)

- **Task lifecycle:**
  - PENDING → ASSIGNED (bot picked it up) → IN_PROGRESS (teleporting/reading) → COMPLETED / FAILED
  - Recurring tasks cycle: COMPLETED → PENDING (rescheduled for next interval)
  - Failed tasks: retry once, then mark FAILED with error details

## Acceptance Criteria

- Java backend can create tasks via POST /api/tasks
- Tasks assigned to available bots automatically
- One-shot VERIFY tasks execute and return result via callback
- Recurring tasks execute on schedule (30 min / 15 min)
- Tasks reassigned when a bot goes offline
- Region grouping minimizes teleport count
- Cancelling a task stops future monitoring runs
- Callback results delivered to Java backend reliably
- Task list endpoint shows current task state

## Notes

- The callback URL approach keeps the bot service decoupled from the Java backend. The bot service doesn't need to know about auctions or escrow - it just checks parcels and reports data.
- For MVP, an in-memory task queue is fine. If the bot service restarts, the Java backend can re-submit active monitoring tasks. Persistent task storage is a future enhancement.
- Region grouping optimization: if bots A needs to check 3 parcels in region "Ahern" and 2 in "Morris", send them to Ahern first (1 teleport, 3 reads), then Morris (1 teleport, 2 reads) instead of alternating.
