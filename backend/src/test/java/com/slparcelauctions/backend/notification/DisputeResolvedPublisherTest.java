package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.admin.disputes.AdminDisputeAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DisputeResolvedPublisherTest {

    private NotificationService service;
    private NotificationPublisherImpl publisher;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        publisher = new NotificationPublisherImpl(service, null, null, null, null);
    }

    @Test
    void recognizePaymentToWinnerSaysPaymentRecognized() {
        publisher.disputeResolved(
                /*recipientUserId*/ 42L, /*role*/ "winner",
                /*auctionId*/ 100L, /*escrowId*/ 200L,
                "Beachfront 1024m²", /*amountL*/ 1031L,
                AdminDisputeAction.RECOGNIZE_PAYMENT,
                /*alsoCancelListing*/ false);

        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(service).publish(cap.capture());
        NotificationEvent event = cap.getValue();
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.category()).isEqualTo(NotificationCategory.DISPUTE_RESOLVED);
        assertThat(event.body()).contains("Payment recognized")
                                .contains("Beachfront 1024m²");
    }

    @Test
    void recognizePaymentToSellerSaysPleaseTransfer() {
        publisher.disputeResolved(7L, "seller", 100L, 200L,
                "Beachfront 1024m²", 1031L,
                AdminDisputeAction.RECOGNIZE_PAYMENT, false);
        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(service).publish(cap.capture());
        assertThat(cap.getValue().body())
                .contains("Dispute resolved")
                .contains("Please transfer");
    }

    @Test
    void resetToFundedToWinnerSaysCompletePayment() {
        publisher.disputeResolved(42L, "winner", 100L, 200L,
                "Beachfront 1024m²", 1031L,
                AdminDisputeAction.RESET_TO_FUNDED, false);
        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(service).publish(cap.capture());
        assertThat(cap.getValue().body())
                .contains("Dispute dismissed")
                .contains("complete payment at the terminal");
    }

    @Test
    void resetToFundedPlusCancelToWinnerSaysRefundIssued() {
        publisher.disputeResolved(42L, "winner", 100L, 200L,
                "Beachfront 1024m²", 1031L,
                AdminDisputeAction.RESET_TO_FUNDED, /*alsoCancel*/ true);
        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(service).publish(cap.capture());
        assertThat(cap.getValue().body())
                .contains("dispute")
                .contains("upheld")
                .contains("L$ 1031")
                .contains("refund");
    }

    @Test
    void markExpiredToWinnerSaysRefundProcessing() {
        publisher.disputeResolved(42L, "winner", 100L, 200L,
                "Da Boom Studio Lot", 850L,
                AdminDisputeAction.MARK_EXPIRED, false);
        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(service).publish(cap.capture());
        assertThat(cap.getValue().body())
                .contains("Escrow expired")
                .contains("refund");
    }

    @Test
    void resumeTransferIsIdenticalForWinnerAndSeller() {
        publisher.disputeResolved(42L, "winner", 100L, 200L,
                "Lot A", 500L, AdminDisputeAction.RESUME_TRANSFER, false);
        publisher.disputeResolved(7L, "seller", 100L, 200L,
                "Lot A", 500L, AdminDisputeAction.RESUME_TRANSFER, false);
        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(service, times(2)).publish(cap.capture());
        assertThat(cap.getAllValues().get(0).body())
                .isEqualTo(cap.getAllValues().get(1).body());
    }
}
