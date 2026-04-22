package com.slparcelauctions.backend.escrow.command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link TerminalHttpClient} that records every POST and responds
 * from a scripted map keyed on {@code idempotencyKey}. Swap into a
 * {@code @SpringBootTest} via {@code @MockitoBean} or a
 * {@code @TestConfiguration} {@code @Primary} bean when a test needs richer
 * behaviour than Mockito default stubs (e.g. fixed / FIFO response queues
 * across attempts for the same command).
 *
 * <p>Default response for an unscripted key is {@link TerminalHttpResult#ok()}
 * so tests that don't care about dispatcher branching still see sensible
 * behaviour. Tests that exercise the retry state machine pre-populate the
 * map with {@link #respondWithFailure(String, String)} to force the
 * FAILED / backoff branch, or {@link #respondWithAck(String)} to stay on
 * the happy IN_FLIGHT path.
 */
public class MockTerminalHttpClient implements TerminalHttpClient {

    public record Posted(String url, TerminalCommandBody body) { }

    public final List<Posted> posts = new CopyOnWriteArrayList<>();
    private final Map<String, TerminalHttpResult> scripted = new ConcurrentHashMap<>();

    @Override
    public TerminalHttpResult post(String url, TerminalCommandBody body) {
        posts.add(new Posted(url, body));
        TerminalHttpResult scriptedResult = scripted.get(body.idempotencyKey());
        if (scriptedResult != null) {
            return scriptedResult;
        }
        return TerminalHttpResult.ok();
    }

    public void respondWithAck(String idempotencyKey) {
        scripted.put(idempotencyKey, TerminalHttpResult.ok());
    }

    public void respondWithFailure(String idempotencyKey, String errorMessage) {
        scripted.put(idempotencyKey, TerminalHttpResult.fail(errorMessage));
    }

    public void reset() {
        posts.clear();
        scripted.clear();
    }
}
