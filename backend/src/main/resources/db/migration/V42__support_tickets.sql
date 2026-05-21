-- V42: customer support contact feature foundation.
--
-- Adds three new tables:
--   support_tickets             - parent thread row (one per ticket)
--   support_ticket_messages     - 1:N child messages (user replies, admin replies,
--                                 admin internal notes, synthetic system messages)
--   support_ticket_attachments  - 1:N child images promoted from the pending
--                                 S3 prefix into the per-message scoped path
--
-- The status model has only two persisted values (OPEN, RESOLVED); the
-- "needs admin attention" admin-queue filter is derived from
-- last_message_author = 'USER' AND status = 'OPEN'. See spec §2 rationale.
--
-- Spec: docs/superpowers/specs/2026-05-21-customer-support-contact-design.md
-- Plan: docs/superpowers/plans/2026-05-21-customer-support-contact-plan.md

CREATE TABLE support_tickets (
    id                       BIGSERIAL PRIMARY KEY,
    public_id                UUID NOT NULL UNIQUE,
    user_id                  BIGINT NOT NULL REFERENCES users(id),
    subject                  VARCHAR(160) NOT NULL,
    category                 VARCHAR(32) NOT NULL,
    status                   VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_admin_id        BIGINT REFERENCES users(id),
    last_message_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_author      VARCHAR(16) NOT NULL,
    resolved_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                  BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT support_ticket_status_valid
        CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT support_ticket_category_valid
        CHECK (category IN ('ACCOUNT','BIDDING','LISTING','ESCROW','WALLET','OTHER')),
    CONSTRAINT support_ticket_last_message_author_valid
        CHECK (last_message_author IN ('USER','ADMIN'))
);
CREATE INDEX support_tickets_user_status_idx
    ON support_tickets(user_id, status);
CREATE INDEX support_tickets_open_admin_queue_idx
    ON support_tickets(status, last_message_author, last_message_at DESC)
    WHERE status = 'OPEN';
CREATE INDEX support_tickets_assigned_admin_idx
    ON support_tickets(assigned_admin_id)
    WHERE assigned_admin_id IS NOT NULL;

CREATE TABLE support_ticket_messages (
    id               BIGSERIAL PRIMARY KEY,
    public_id        UUID NOT NULL UNIQUE,
    ticket_id        BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    author_user_id   BIGINT NOT NULL REFERENCES users(id),
    author_role      VARCHAR(16) NOT NULL,
    body             TEXT NOT NULL,
    visible_to_user  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT support_ticket_message_author_role_valid
        CHECK (author_role IN ('USER','ADMIN'))
);
CREATE INDEX support_ticket_messages_ticket_idx
    ON support_ticket_messages(ticket_id, created_at);

CREATE TABLE support_ticket_attachments (
    id           BIGSERIAL PRIMARY KEY,
    public_id    UUID NOT NULL UNIQUE,
    message_id   BIGINT NOT NULL REFERENCES support_ticket_messages(id) ON DELETE CASCADE,
    storage_key  VARCHAR(255) NOT NULL,
    mime_type    VARCHAR(64) NOT NULL,
    size_bytes   INTEGER NOT NULL,
    width        INTEGER,
    height       INTEGER,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX support_ticket_attachments_message_idx
    ON support_ticket_attachments(message_id);
