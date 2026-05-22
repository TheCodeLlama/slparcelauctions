package com.slparcelauctions.backend.auction.search;

/**
 * The light + dark variant URL pair for a search row's primary photo.
 *
 * <p>{@code lightUrl} is always present for an existing primary photo
 * (the entity's {@code light_object_key} is {@code NOT NULL});
 * {@code darkUrl} is {@code null} when that photo carries no dark
 * variant. An auction with no photos at all is simply absent from the
 * batch loader's map — there is no "empty pair" sentinel.
 */
public record PrimaryPhotoUrls(String lightUrl, String darkUrl) {
}
