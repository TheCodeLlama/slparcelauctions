package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown by leader-side bulk member-edit paths when an entry in the batch references a
 * member public-id that does not exist within the addressed group. Surfaced at the wire
 * as {@code 400 MEMBER_NOT_IN_GROUP} so the caller can highlight the bad row in their
 * payload. Spec §6.7 / §15.1.
 *
 * <p>Distinct from {@link RealtyGroupNotFoundException}: this exception means the
 * group resolved fine but a specific member-row in the batch is wrong. Use this for
 * batch entry validation; use {@link RealtyGroupNotFoundException} when the group
 * itself is missing.
 */
@Getter
public class MemberNotInGroupException extends RuntimeException {
    private final UUID memberPublicId;

    public MemberNotInGroupException(UUID memberPublicId) {
        super("Member not in group: " + memberPublicId);
        this.memberPublicId = memberPublicId;
    }
}
