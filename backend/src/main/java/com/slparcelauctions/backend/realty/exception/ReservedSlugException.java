package com.slparcelauctions.backend.realty.exception;

import lombok.Getter;

/**
 * Thrown when a group name would derive to (or a rename would land on) a slug
 * that is reserved by the {@code /groups} URL namespace - for example
 * {@code new}, {@code me}, {@code invitations}. These collide with router
 * segments under {@code /groups/...} that must always resolve to the
 * application's own pages, never to a user-named group.
 *
 * <p>Surfaced to the wire by {@link RealtyExceptionHandler} as
 * {@code 422 Unprocessable Entity} with code {@code RESERVED_SLUG}; the body
 * carries the offending {@code slug} so the frontend can render an inline
 * "pick a different name" error.
 */
@Getter
public class ReservedSlugException extends RuntimeException {

    private final String slug;

    public ReservedSlugException(String slug) {
        super("Slug '" + slug + "' is reserved");
        this.slug = slug;
    }
}
