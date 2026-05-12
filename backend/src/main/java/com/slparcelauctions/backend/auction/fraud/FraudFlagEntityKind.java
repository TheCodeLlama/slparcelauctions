package com.slparcelauctions.backend.auction.fraud;

/**
 * Discriminator for the kind of entity a {@link FraudFlag} is raised against.
 *
 * <p>Pre-F the fraud_flags table was implicitly auction-keyed; V28 added the
 * {@code entity_type} column with a {@code 'LISTING'} default and widened the
 * check constraint to admit {@code USER} and {@code REALTY_GROUP}. The
 * non-LISTING values are populated by sub-project F flows (e.g. admin
 * fraud-flagging a realty group from the moderation surface). Sub-project F
 * spec §4.5.
 */
public enum FraudFlagEntityKind {
    USER,
    LISTING,
    REALTY_GROUP
}
