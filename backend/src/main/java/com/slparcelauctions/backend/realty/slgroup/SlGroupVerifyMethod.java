package com.slparcelauctions.backend.realty.slgroup;

/**
 * Which verification path actually flipped a {@link RealtyGroupSlGroup} row to
 * {@code verified=true}. {@code null} while the row is still pending.
 *
 * <ul>
 *   <li>{@code FOUNDER_TERMINAL} -- the SL group's founder stepped onto an SLPA terminal and
 *       typed the verification code; backend cross-checked the avatar UUID against the SL
 *       group's founder via the World API.</li>
 * </ul>
 *
 * <p>Kept as a single-value enum for forward-compatibility with future verification methods.
 */
public enum SlGroupVerifyMethod {
    FOUNDER_TERMINAL
}
