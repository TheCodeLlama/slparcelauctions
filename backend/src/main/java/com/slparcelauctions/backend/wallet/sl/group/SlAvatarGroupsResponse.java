package com.slparcelauctions.backend.wallet.sl.group;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/sl/wallet/avatar-groups}. Returns the
 * groups the avatar can deposit into (leader-owned OR holding the
 * {@code DEPOSIT_TO_GROUP_WALLET} permission), alphabetised by group name and
 * paginated.
 *
 * <p>{@code hasMore} is true when the underlying query returned more rows than
 * the page size; in that case {@code nextAfter} holds the last group name on
 * this page and should be passed back as the cursor for the next request.
 *
 * <p>Empty list is the contract for "no eligible groups" — the LSL caller
 * renders that as a "no groups" dialog rather than an error.
 */
public record SlAvatarGroupsResponse(
        List<GroupRef> groups,
        boolean hasMore,
        String nextAfter
) {
    public record GroupRef(UUID publicId, String name) { }
}
