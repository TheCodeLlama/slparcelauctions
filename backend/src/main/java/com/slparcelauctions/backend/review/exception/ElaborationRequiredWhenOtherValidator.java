package com.slparcelauctions.backend.review.exception;

import com.slparcelauctions.backend.review.ReviewFlagReason;
import com.slparcelauctions.backend.review.dto.ReviewFlagRequest;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementation of {@link ElaborationRequiredWhenOther}. When {@code
 * reason=OTHER} we require a non-null, non-blank {@code elaboration};
 * every other reason is valid regardless of the elaboration value. The
 * violation is attached to the {@code elaboration} field (not the class)
 * so the default {@code errors} map rendered by {@code
 * GlobalExceptionHandler} surfaces the message under an explicit field
 * key for the frontend.
 */
public class ElaborationRequiredWhenOtherValidator
        implements ConstraintValidator<ElaborationRequiredWhenOther, ReviewFlagRequest> {

    @Override
    public boolean isValid(ReviewFlagRequest request, ConstraintValidatorContext ctx) {
        if (request == null) {
            // A null body is handled upstream by Jackson / @RequestBody;
            // returning true here defers to that layer rather than
            // shadowing it with a confusing class-level message.
            return true;
        }
        if (request.reason() != ReviewFlagReason.OTHER) {
            return true;
        }
        String elab = request.elaboration();
        boolean ok = elab != null && !elab.isBlank();
        if (!ok) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(
                            "Elaboration is required when reason is OTHER.")
                    .addPropertyNode("elaboration")
                    .addConstraintViolation();
        }
        return ok;
    }
}
