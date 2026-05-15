package com.slparcelauctions.backend.wallet.sl.group;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/sl/wallet/avatar-groups}. Posted by the in-world
 * SLParcels Terminal when an avatar touches "Pay to group" — the terminal asks
 * the backend for the list of groups this avatar is allowed to deposit into,
 * then renders them as dialog buttons.
 *
 * <p>{@code after} is an optional cursor (alphabetical group name, exclusive)
 * for the "More" button — passing {@code null} returns the first page.
 */
public record SlAvatarGroupsRequest(
        @NotBlank String terminalId,
        @NotBlank String sharedSecret,
        @NotBlank String avatarUuid,
        String after
) { }
