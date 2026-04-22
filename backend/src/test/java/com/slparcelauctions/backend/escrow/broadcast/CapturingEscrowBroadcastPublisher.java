package com.slparcelauctions.backend.escrow.broadcast;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-support implementation of {@link EscrowBroadcastPublisher} that
 * stores every published envelope in thread-safe lists. Swap this into a
 * {@code @SpringBootTest} slice via a {@code @TestConfiguration} with a
 * {@code @Primary} bean definition when richer envelope-field assertions
 * are useful than a plain Mockito mock.
 *
 * <p>Using {@link CopyOnWriteArrayList} keeps the publisher safe to call
 * from scheduler + bid-placement threads concurrently — tests exercising
 * race scenarios can rely on iteration not throwing
 * {@code ConcurrentModificationException}. Method set grows per task as
 * new envelope variants land on {@link EscrowBroadcastPublisher}.
 */
public class CapturingEscrowBroadcastPublisher implements EscrowBroadcastPublisher {

    public final List<EscrowCreatedEnvelope> created = new CopyOnWriteArrayList<>();
    public final List<EscrowDisputedEnvelope> disputed = new CopyOnWriteArrayList<>();
    public final List<EscrowFundedEnvelope> funded = new CopyOnWriteArrayList<>();

    @Override
    public void publishCreated(EscrowCreatedEnvelope envelope) {
        created.add(envelope);
    }

    @Override
    public void publishDisputed(EscrowDisputedEnvelope envelope) {
        disputed.add(envelope);
    }

    @Override
    public void publishFunded(EscrowFundedEnvelope envelope) {
        funded.add(envelope);
    }

    /** Clears every capture list; useful in {@code @BeforeEach} hooks. */
    public void reset() {
        created.clear();
        disputed.clear();
        funded.clear();
    }
}
