package com.slparcelauctions.backend.review.exception;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Class-level cross-field validator for
 * {@link com.slparcelauctions.backend.review.dto.ReviewFlagRequest}: when
 * {@code reason=OTHER}, {@code elaboration} must be a non-blank string.
 * Every other enum value accepts a null/blank elaboration. Enforcing
 * this at the DTO layer (not the entity) keeps the constraint visible to
 * the API surface — violations render as 400 with a per-field message
 * via {@code GlobalExceptionHandler.handleValidation}.
 */
@Documented
@Constraint(validatedBy = ElaborationRequiredWhenOtherValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ElaborationRequiredWhenOther {

    String message() default "Elaboration is required when reason is OTHER.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
