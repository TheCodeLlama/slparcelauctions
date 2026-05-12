package com.slparcelauctions.backend.realty.slgroup.dto;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;

@Component
public class RealtyGroupSlGroupDtoMapper {

    public RealtyGroupSlGroupDto toDto(RealtyGroupSlGroup row) {
        SlGroupPendingDto pending = row.isVerified() ? null : new SlGroupPendingDto(
                row.getVerificationCode(),
                row.getVerificationCodeExpiresAt());
        return new RealtyGroupSlGroupDto(
                row.getPublicId(),
                row.getSlGroupUuid(),
                row.getSlGroupName(),
                row.isVerified(),
                row.getVerifiedAt(),
                row.getVerifiedVia(),
                pending,
                row.getFounderAvatarUuid());
    }
}
