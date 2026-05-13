-- V31: notification.link_url
--
-- Adds a nullable VARCHAR(512) column that carries an in-app deeplink target
-- for the bell row + /notifications row. Populated by upstream publishers
-- where a one-click destination is meaningful (e.g. realty-group invitation
-- routes the invitee to /groups/invitations/me per design §5.8).
--
-- Existing rows are backfilled to NULL (the default); the column is non-NOT-NULL
-- because most notification categories do not surface a per-row deeplink.

ALTER TABLE notification ADD COLUMN IF NOT EXISTS link_url VARCHAR(512);
