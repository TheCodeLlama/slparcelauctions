package com.slparcelauctions.backend.support;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.support.dto.AdminSupportTicketQueueRow;
import com.slparcelauctions.backend.support.dto.SupportTicketAttachmentDto;
import com.slparcelauctions.backend.support.dto.SupportTicketDto;
import com.slparcelauctions.backend.support.dto.SupportTicketMessageDto;
import com.slparcelauctions.backend.support.dto.SupportTicketSummaryDto;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;

/**
 * Maps support-ticket entities to DTOs. {@link #toUserDto} filters out
 * messages with {@code visibleToUser=false} (admin internal notes);
 * {@link #toAdminDto} includes every message.
 *
 * <p>Because {@link SupportTicket#getMessages} is a {@code Set} (Hibernate
 * {@code MultipleBagFetchException} avoidance, see Task 1), this mapper
 * sorts messages by {@code createdAt} ascending so the thread renders in
 * conversation order. Attachments within a message are sorted the same
 * way.
 */
@Component
@RequiredArgsConstructor
public class SupportTicketMapper {

    public SupportTicketAttachmentDto toAttachmentDto(SupportTicketAttachment a) {
        return new SupportTicketAttachmentDto(
                a.getPublicId(), a.getMimeType(), a.getSizeBytes(),
                a.getWidth(), a.getHeight());
    }

    public SupportTicketMessageDto toMessageDto(SupportTicketMessage m) {
        User author = m.getAuthorUser();
        List<SupportTicketAttachmentDto> atts = m.getAttachments().stream()
                .sorted(Comparator.comparing(SupportTicketAttachment::getCreatedAt))
                .map(this::toAttachmentDto)
                .toList();
        return new SupportTicketMessageDto(
                m.getPublicId(),
                author.getPublicId(),
                displayNameOf(author),
                m.getAuthorRole(),
                m.getBody(),
                Boolean.TRUE.equals(m.getVisibleToUser()),
                m.getCreatedAt(),
                atts);
    }

    public SupportTicketDto toUserDto(SupportTicket t, User assignedAdmin) {
        List<SupportTicketMessageDto> msgs = t.getMessages().stream()
                .filter(msg -> Boolean.TRUE.equals(msg.getVisibleToUser()))
                .sorted(Comparator.comparing(SupportTicketMessage::getCreatedAt))
                .map(this::toMessageDto)
                .toList();
        return buildDto(t, assignedAdmin, msgs);
    }

    public SupportTicketDto toAdminDto(SupportTicket t, User assignedAdmin) {
        List<SupportTicketMessageDto> msgs = t.getMessages().stream()
                .sorted(Comparator.comparing(SupportTicketMessage::getCreatedAt))
                .map(this::toMessageDto)
                .toList();
        return buildDto(t, assignedAdmin, msgs);
    }

    public SupportTicketSummaryDto toSummaryDto(SupportTicket t) {
        return new SupportTicketSummaryDto(
                t.getPublicId(), t.getSubject(), t.getCategory(), t.getStatus(),
                t.getLastMessageAuthor(), t.getLastMessageAt());
    }

    public AdminSupportTicketQueueRow toAdminRow(SupportTicket t, User assignedAdmin) {
        User submitter = t.getUser();
        return new AdminSupportTicketQueueRow(
                t.getPublicId(),
                t.getSubject(),
                t.getCategory(),
                t.getStatus(),
                submitter.getPublicId(),
                displayNameOf(submitter),
                assignedAdmin == null ? null : assignedAdmin.getPublicId(),
                assignedAdmin == null ? null : displayNameOf(assignedAdmin),
                t.getLastMessageAuthor(),
                t.getLastMessageAt());
    }

    private SupportTicketDto buildDto(SupportTicket t, User assignedAdmin,
                                      List<SupportTicketMessageDto> messages) {
        User submitter = t.getUser();
        return new SupportTicketDto(
                t.getPublicId(),
                submitter.getPublicId(),
                displayNameOf(submitter),
                t.getSubject(),
                t.getCategory(),
                t.getStatus(),
                assignedAdmin == null ? null : assignedAdmin.getPublicId(),
                assignedAdmin == null ? null : displayNameOf(assignedAdmin),
                t.getLastMessageAt(),
                t.getLastMessageAuthor(),
                t.getResolvedAt(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                messages);
    }

    private static String displayNameOf(User u) {
        return u.getDisplayName() == null ? u.getUsername() : u.getDisplayName();
    }
}
