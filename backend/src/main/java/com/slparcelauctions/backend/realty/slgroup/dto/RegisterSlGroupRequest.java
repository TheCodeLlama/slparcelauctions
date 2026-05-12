package com.slparcelauctions.backend.realty.slgroup.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RegisterSlGroupRequest(@NotNull UUID slGroupUuid) {}
