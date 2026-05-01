package com.slparcelauctions.backend.wallet.me;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptTermsRequest(
        @NotBlank @Size(max = 16) String termsVersion
) { }
