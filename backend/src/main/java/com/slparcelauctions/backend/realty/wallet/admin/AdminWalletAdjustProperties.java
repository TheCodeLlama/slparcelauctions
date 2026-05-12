package com.slparcelauctions.backend.realty.wallet.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Sub-project G section 7.2 -- admin wallet adjustment knobs. The ceiling is a
 * sanity gate against fat-finger mistakes (the prod policy lever is admin
 * review, not the cap). Tuneable via config -- operators dial it up if a
 * legitimate large adjustment is ever blocked.
 *
 * <p>Bound from {@code slpa.realty.admin-wallet-adjust-max-l}; default
 * 10,000,000 L$.
 */
@ConfigurationProperties(prefix = "slpa.realty")
@Getter
@Setter
public class AdminWalletAdjustProperties {

    @Min(1)
    private long adminWalletAdjustMaxL = 10_000_000L;
}
