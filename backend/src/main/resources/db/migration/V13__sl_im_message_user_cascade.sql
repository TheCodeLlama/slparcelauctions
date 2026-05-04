-- V13: Add ON DELETE CASCADE to sl_im_message.user_id FK.
--
-- V12 created sl_im_message with a bare REFERENCES users(id) (NO ACTION).
-- Deleting a user while they have pending IM messages triggered a FK violation
-- in test cleanup (and would do the same in production if a user account were
-- ever removed). IM messages belong to the user; cascade-deleting them when
-- the user is removed is the correct semantic.

ALTER TABLE sl_im_message
    DROP CONSTRAINT sl_im_message_user_id_fkey;

ALTER TABLE sl_im_message
    ADD CONSTRAINT sl_im_message_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
