package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

/**
 * Typed wrapper for the relevant fields of {@code world.secondlife.com/group/{uuid}} that
 * realty-group SL-group verification cares about. {@code aboutText} is the full About /
 * Charter text the leader edits (where the verification code is pasted in the about-text
 * flow). {@code founderUuid} is the SL avatar UUID of the founder (cross-checked against
 * the founder-terminal callback). {@code name} is the SL group's display name (recorded
 * onto the registration row for UI labels).
 *
 * <p>Any field other than {@code slGroupUuid} may be {@code null} if the parser cannot
 * extract it from the World API HTML — callers decide whether a missing field is fatal.
 *
 * <p><b>Parser fidelity:</b> the selectors in {@link com.slparcelauctions.backend.sl.SlWorldApiClient#fetchGroupPage(UUID)}
 * are best-effort against the assumed shape of {@code world.secondlife.com/group/{uuid}}.
 * If parsing returns {@code null} for fields that should be present, validate the patterns
 * against a fresh {@code curl world.secondlife.com/group/{uuid}} response and adjust the
 * selectors.
 */
public record GroupPageData(
        UUID slGroupUuid,
        String name,
        String aboutText,
        UUID founderUuid
) {}
