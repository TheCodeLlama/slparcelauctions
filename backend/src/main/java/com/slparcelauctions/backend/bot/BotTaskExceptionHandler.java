package com.slparcelauctions.backend.bot;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.slparcelauctions.backend.bot.exception.BotTaskNotClaimedException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Bot-scoped exception handler. The {@link BotTaskController} sits
 * outside the auction package, so {@code AuctionExceptionHandler}'s
 * {@code basePackages = "...auction"} scoping does not see exceptions
 * thrown by {@link BotTaskService}. This handler exposes RFC 9457
 * error shapes for the remaining bot surface (claim queue +
 * future-extension task types):
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} -> 400 {@code INVALID_REQUEST}</li>
 *   <li>{@link BotTaskNotFoundException} -> 404 {@code BOT_TASK_NOT_FOUND}</li>
 *   <li>{@link BotTaskNotClaimedException} -> 409 {@code BOT_TASK_NOT_CLAIMED}</li>
 * </ul>
 *
 * <p>Runs at {@code HIGHEST_PRECEDENCE + 10} so it intercepts before
 * the global catch-all.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.bot")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class BotTaskExceptionHandler {

    /**
     * Propagates {@link ResponseStatusException} from bot services (e.g.
     * {@link com.slparcelauctions.backend.auction.parcelscan.ParcelScanService})
     * as the correct HTTP status. Without this, the global catch-all
     * {@code Exception.class} handler swallows it as a 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.resolve(e.getStatusCode().value()), e.getReason());
        pd.setTitle("Request failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_REQUEST_FAILED");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_REQUEST");
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
}
