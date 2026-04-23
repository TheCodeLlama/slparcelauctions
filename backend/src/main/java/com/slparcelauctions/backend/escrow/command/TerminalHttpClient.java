package com.slparcelauctions.backend.escrow.command;

/**
 * Abstraction over the outbound HTTP call to an in-world terminal's
 * {@code http-in} URL. The production bean is {@link TerminalHttpClientImpl}
 * (Spring {@code RestClient}); tests substitute a scripted mock so the
 * dispatcher's state machine can be exercised without a real terminal.
 *
 * <p>Returns a {@link TerminalHttpResult} rather than throwing on network
 * failures — the dispatcher differentiates the two synchronously (transport
 * failure now means "schedule backoff"; a 2xx ACK means "wait for async
 * callback"). The terminal's business-level success / failure is delivered
 * later via {@link PayoutResultController}.
 */
public interface TerminalHttpClient {

    TerminalHttpResult post(String url, TerminalCommandBody body);

    record TerminalHttpResult(boolean ack, String errorMessage) {
        public static TerminalHttpResult ok() {
            return new TerminalHttpResult(true, null);
        }

        public static TerminalHttpResult fail(String err) {
            return new TerminalHttpResult(false, err);
        }
    }
}
