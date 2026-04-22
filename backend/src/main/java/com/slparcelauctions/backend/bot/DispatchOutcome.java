package com.slparcelauctions.backend.bot;

/**
 * Return value from {@link BotMonitorDispatcher#dispatch}. The
 * {@code shouldReArm} bit controls whether the monitor row is re-armed
 * (PENDING + bumped {@code nextRunAt}) or left alone (lifecycle hook
 * already cancelled it as part of the triggered business transition).
 * See spec §6 — transaction-ordering hazard note on TRANSFER_COMPLETE.
 *
 * @param shouldReArm {@code true} -> caller bumps nextRunAt + sets PENDING;
 *                    {@code false} -> caller leaves the row to whatever the
 *                    downstream hook wrote (CANCELLED, typically).
 * @param logAction   one-word string describing what the dispatcher did,
 *                    for the structured log line.
 */
public record DispatchOutcome(boolean shouldReArm, String logAction) {}
