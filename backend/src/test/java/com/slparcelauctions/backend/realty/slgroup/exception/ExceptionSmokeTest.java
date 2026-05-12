package com.slparcelauctions.backend.realty.slgroup.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExceptionSmokeTest {

    @Test
    void exceptionsCarryStableCodes() {
        assertThat(SlGroupAlreadyRegisteredException.CODE).isEqualTo("SL_GROUP_ALREADY_REGISTERED");
        assertThat(SlGroupNotVerifiedException.CODE).isEqualTo("SL_GROUP_NOT_VERIFIED");
        assertThat(SlGroupVerificationExpiredException.CODE).isEqualTo("SL_GROUP_VERIFICATION_EXPIRED");
        assertThat(SlGroupFounderMismatchException.CODE).isEqualTo("SL_GROUP_FOUNDER_MISMATCH");
        assertThat(ParcelNotOwnedByRegisteredSlGroupException.CODE)
                .isEqualTo("PARCEL_NOT_OWNED_BY_REGISTERED_SL_GROUP");
        assertThat(RegisteredSlGroupHasListingsException.CODE)
                .isEqualTo("REGISTERED_SL_GROUP_HAS_LISTINGS");
        assertThat(com.slparcelauctions.backend.realty.exception
                .SlGroupRegisteredBlocksDissolveException.CODE).isEqualTo("SL_GROUPS_BLOCK_DISSOLVE");
        assertThat(com.slparcelauctions.backend.auction.exception
                .BrokerCancelNotApplicableException.CODE).isEqualTo("BROKER_CANCEL_NOT_APPLICABLE");
    }

    @Test
    void carryUuidContext() {
        UUID id = UUID.randomUUID();
        SlGroupAlreadyRegisteredException ex = new SlGroupAlreadyRegisteredException(id);
        assertThat(ex.getSlGroupUuid()).isEqualTo(id);
    }
}
