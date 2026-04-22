package com.slparcelauctions.backend.bot;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.bot.exception.BotEscrowTerminalException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotClaimedException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotFoundException;
import com.slparcelauctions.backend.bot.exception.BotTaskWrongTypeException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Bot-scoped exception handler. The production {@link BotTaskController} and
 * dev stub {@link DevBotTaskController} sit outside the auction package, so
 * {@code AuctionExceptionHandler}'s {@code basePackages = "...auction"}
 * scoping does not see exceptions thrown by {@link BotTaskService}. This
 * handler re-exposes the same RFC 9457 error shapes for the bot surface:
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} → 400 {@code INVALID_REQUEST}</li>
 *   <li>{@link InvalidAuctionStateException} → 409 {@code AUCTION_INVALID_STATE}</li>
 *   <li>{@link ParcelAlreadyListedException} → 409 {@code PARCEL_ALREADY_LISTED}</li>
 *   <li>{@link BotTaskNotFoundException} → 404 {@code BOT_TASK_NOT_FOUND}</li>
 *   <li>{@link BotTaskNotClaimedException} → 409 {@code BOT_TASK_NOT_CLAIMED}</li>
 *   <li>{@link BotTaskWrongTypeException} → 409 {@code BOT_TASK_WRONG_TYPE}</li>
 *   <li>{@link BotEscrowTerminalException} → 409 {@code BOT_ESCROW_TERMINAL}</li>
 * </ul>
 *
 * <p>Runs at {@code HIGHEST_PRECEDENCE + 10} so it intercepts before the
 * global catch-all.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.bot")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class BotTaskExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_REQUEST");
        return pd;
    }

    @ExceptionHandler(InvalidAuctionStateException.class)
    public ProblemDetail handleInvalidState(InvalidAuctionStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Invalid Auction State");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUCTION_INVALID_STATE");
        pd.setProperty("auctionId", e.getAuctionId());
        pd.setProperty("currentState", e.getCurrentState().name());
        pd.setProperty("attemptedAction", e.getAttemptedAction());
        return pd;
    }

    @ExceptionHandler(ParcelAlreadyListedException.class)
    public ProblemDetail handleParcelAlreadyListed(
            ParcelAlreadyListedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This parcel is currently in an active auction. "
                        + "You can list it again after that auction ends.");
        pd.setTitle("Parcel Already Listed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_ALREADY_LISTED");
        pd.setProperty("parcelId", e.getParcelId());
        pd.setProperty("blockingAuctionId", e.getBlockingAuctionId());
        return pd;
    }

    @ExceptionHandler(BotTaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(
            BotTaskNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Bot Task Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_TASK_NOT_FOUND");
        pd.setProperty("taskId", e.getTaskId());
        return pd;
    }

    @ExceptionHandler(BotTaskNotClaimedException.class)
    public ProblemDetail handleNotClaimed(
            BotTaskNotClaimedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Bot Task Not Claimed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_TASK_NOT_CLAIMED");
        pd.setProperty("taskId", e.getTaskId());
        pd.setProperty("currentStatus", e.getCurrentStatus().name());
        return pd;
    }

    @ExceptionHandler(BotTaskWrongTypeException.class)
    public ProblemDetail handleWrongType(
            BotTaskWrongTypeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Bot Task Wrong Type");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_TASK_WRONG_TYPE");
        pd.setProperty("taskId", e.getTaskId());
        pd.setProperty("actual", e.getActual().name());
        pd.setProperty("expected", e.getExpected().name());
        return pd;
    }

    @ExceptionHandler(BotEscrowTerminalException.class)
    public ProblemDetail handleEscrowTerminal(
            BotEscrowTerminalException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Escrow Terminal");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_ESCROW_TERMINAL");
        pd.setProperty("escrowId", e.getEscrowId());
        pd.setProperty("state", e.getState().name());
        return pd;
    }
}
