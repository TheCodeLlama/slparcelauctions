package com.slparcelauctions.backend.realty.slgroup;

/**
 * Which verification path actually flipped a {@link RealtyGroupSlGroup} row to
 * {@code verified=true}. {@code null} while the row is still pending.
 *
 * <ul>
 *   <li>{@code ABOUT_TEXT} -- the leader put the verification code in the SL group's About text
 *       and the about-text poll task observed it.</li>
 *   <li>{@code FOUNDER_TERMINAL} -- the SL group's founder stepped onto an SLPA terminal and
 *       typed the verification code; backend cross-checked the avatar UUID against the SL
 *       group's founder via the World API.</li>
 * </ul>
 */
public enum SlGroupVerifyMethod {
    ABOUT_TEXT,
    FOUNDER_TERMINAL
}
