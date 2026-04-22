package com.slparcelauctions.backend.escrow.command;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Production {@link TerminalHttpClient}. Uses Spring's {@link RestClient} to
 * POST {@link TerminalCommandBody} payloads to a terminal's
 * {@code http-in} URL (spec §7.3).
 *
 * <p>Transport-level errors (DNS failure, connection timeout, non-2xx
 * status) are caught and returned as {@link TerminalHttpResult#fail} rather
 * than bubbling up, so the dispatcher's per-command transaction can record
 * the failure and apply backoff without aborting the rest of the sweep.
 * Business-level success or failure arrives asynchronously via the terminal
 * calling {@code POST /api/v1/sl/escrow/payout-result}; this class only
 * reports whether the POST itself was ACKed.
 */
@Component
@Slf4j
public class TerminalHttpClientImpl implements TerminalHttpClient {

    private final RestClient restClient = RestClient.builder().build();

    @Override
    public TerminalHttpResult post(String url, TerminalCommandBody body) {
        try {
            restClient.post()
                    .uri(url)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return TerminalHttpResult.ok();
        } catch (RestClientException e) {
            log.warn("Terminal POST to {} failed: {}", url, e.getMessage());
            return TerminalHttpResult.fail(e.getMessage());
        }
    }
}
