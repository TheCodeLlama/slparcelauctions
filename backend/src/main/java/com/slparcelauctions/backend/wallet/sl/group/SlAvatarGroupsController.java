package com.slparcelauctions.backend.wallet.sl.group;

import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SL-headers + shared-secret gated lookup endpoint for the in-world terminal's
 * "Pay to group" flow.
 *
 * <p>The terminal posts after the avatar touches "Pay to group" so it can
 * render a dialog of every realty group the avatar may deposit into. A user is
 * eligible if they lead the group OR they hold the
 * {@code DEPOSIT_TO_GROUP_WALLET} permission as an agent. Suspended or
 * dissolved groups are filtered out.
 *
 * <p>This endpoint never throws on "no eligible groups" / unknown avatar /
 * unparseable UUID / unknown terminal — those all return an empty list (HTTP
 * 200) so the LSL caller can render a single "no eligible groups" path.
 * Auth-stage failures (bad headers, secret mismatch) bubble through
 * {@code WalletSlExceptionHandler} which is package-scoped to
 * {@code com.slparcelauctions.backend.wallet.sl} and therefore covers this
 * sub-package too.
 *
 * <p>See design spec §4.2.
 */
@RestController
@RequestMapping("/api/v1/sl/wallet")
@RequiredArgsConstructor
@Slf4j
public class SlAvatarGroupsController {

    /**
     * Page size for the group dialog. The LSL {@code llDialog} grid is 12
     * buttons (3 columns × 4 rows) — pick the same number so one backend page
     * maps to exactly one dialog page.
     */
    private static final int PAGE_SIZE = 12;

    private final TerminalService terminalService;
    private final TerminalRepository terminalRepository;
    private final SlHeaderValidator headerValidator;
    private final UserRepository userRepository;
    private final RealtyGroupMemberRepository memberRepository;

    @PostMapping("/avatar-groups")
    @Transactional(readOnly = true)
    public SlAvatarGroupsResponse avatarGroups(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlAvatarGroupsRequest req) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(req.sharedSecret());

        // Empty list (not error) for: unknown terminal, unparseable avatar
        // UUID, avatar with no linked SLParcels account. The LSL caller will
        // render "no eligible groups" in all these cases — no L$ is in flight,
        // so there is nothing to refund.
        if (!terminalRepository.existsById(req.terminalId())) {
            return new SlAvatarGroupsResponse(List.of(), false, null);
        }
        terminalService.markSeen(req.terminalId());

        UUID avatarUuid;
        try {
            avatarUuid = UUID.fromString(req.avatarUuid());
        } catch (IllegalArgumentException e) {
            return new SlAvatarGroupsResponse(List.of(), false, null);
        }
        Long userId = userRepository.findIdBySlAvatarUuid(avatarUuid).orElse(null);
        if (userId == null) {
            return new SlAvatarGroupsResponse(List.of(), false, null);
        }

        // PAGE_SIZE + 1 trick: fetch one extra row and use its presence to set
        // hasMore. Avoids a second COUNT query.
        List<RealtyGroupMemberRepository.GroupNameAndPublicIdProjection> rows =
                memberRepository.findGroupsAvatarCanDepositTo(userId, req.after(), PAGE_SIZE + 1);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<RealtyGroupMemberRepository.GroupNameAndPublicIdProjection> page =
                hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        List<SlAvatarGroupsResponse.GroupRef> groups = page.stream()
                .map(p -> new SlAvatarGroupsResponse.GroupRef(p.getPublicId(), p.getName()))
                .toList();
        String nextAfter = hasMore ? page.get(page.size() - 1).getName() : null;
        return new SlAvatarGroupsResponse(groups, hasMore, nextAfter);
    }
}
