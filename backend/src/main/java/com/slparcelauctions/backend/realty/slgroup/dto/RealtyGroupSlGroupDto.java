package com.slparcelauctions.backend.realty.slgroup.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.slgroup.SlGroupVerifyMethod;

public record RealtyGroupSlGroupDto(
        UUID publicId,
        UUID slGroupUuid,
        String slGroupName,
        boolean verified,
        OffsetDateTime verifiedAt,
        SlGroupVerifyMethod verifiedVia,
        SlGroupPendingDto pending,
        UUID founderAvatarUuid
) {}
