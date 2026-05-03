package com.slparcelauctions.backend.wallet.exception;

/**
 * Thrown when a user attempts a wallet-debiting action (e.g. paying a
 * listing fee from wallet) before accepting the wallet terms of use.
 *
 * <p>Maps to 403 {@code WALLET_TERMS_NOT_ACCEPTED} on the affected endpoints.
 */
public class WalletTermsNotAcceptedException extends RuntimeException {
    public WalletTermsNotAcceptedException() {
        super("wallet terms must be accepted before this action");
    }
}
