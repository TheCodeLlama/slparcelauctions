package com.slparcelauctions.backend.realty.wallet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GroupWithdrawRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validation_rejects_whenRecipientIsNull() {
        GroupWithdrawRequest req = new GroupWithdrawRequest(100L, UUID.randomUUID(), null);
        Set<ConstraintViolation<GroupWithdrawRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "recipient".equals(v.getPropertyPath().toString()));
    }

    @Test
    void validation_accepts_whenRecipientIsAvatar() {
        GroupWithdrawRequest req = new GroupWithdrawRequest(
                100L, UUID.randomUUID(), GroupWithdrawRecipient.AVATAR);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void validation_accepts_whenRecipientIsSlGroup() {
        GroupWithdrawRequest req = new GroupWithdrawRequest(
                100L, UUID.randomUUID(), GroupWithdrawRecipient.SL_GROUP);
        assertThat(validator.validate(req)).isEmpty();
    }
}
