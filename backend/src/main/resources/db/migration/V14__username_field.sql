-- V14: add username login identifier.
--
-- Adds users.username (case-preserved, NFC-normalized, trimmed, internal-
-- whitespace-collapsed at the application layer). Uniqueness is case-
-- insensitive via a functional unique index on LOWER(username).
--
-- The DB is wiped before this ships (paying-customers-mode hasn't started),
-- so this migration runs against a freshly-created schema where the users
-- table has no rows. NOT NULL without DEFAULT is therefore safe.
--
-- The pre-existing UNIQUE on users.email (V1 constraint
-- uk6dotkott2kjsp8vw4d0m25fb7) is intentionally left in place — email stays
-- in the model as a future-notification channel and "two accounts can't
-- claim the same email address" remains correct.

ALTER TABLE users
    ADD COLUMN username varchar(64) NOT NULL;

CREATE UNIQUE INDEX users_username_lower
    ON users (LOWER(username));
