-- realty_groups: cover (rename) + cover_dark (new) + logo (rename) + logo_dark (new) + default_listing (new pair)
ALTER TABLE realty_groups RENAME COLUMN cover_object_key   TO cover_light_object_key;
ALTER TABLE realty_groups RENAME COLUMN cover_content_type TO cover_light_content_type;
ALTER TABLE realty_groups RENAME COLUMN cover_size_bytes   TO cover_light_size_bytes;
ALTER TABLE realty_groups
  ADD COLUMN cover_dark_object_key   VARCHAR(500),
  ADD COLUMN cover_dark_content_type VARCHAR(100),
  ADD COLUMN cover_dark_size_bytes   BIGINT;

ALTER TABLE realty_groups RENAME COLUMN logo_object_key   TO logo_light_object_key;
ALTER TABLE realty_groups RENAME COLUMN logo_content_type TO logo_light_content_type;
ALTER TABLE realty_groups RENAME COLUMN logo_size_bytes   TO logo_light_size_bytes;
ALTER TABLE realty_groups
  ADD COLUMN logo_dark_object_key   VARCHAR(500),
  ADD COLUMN logo_dark_content_type VARCHAR(100),
  ADD COLUMN logo_dark_size_bytes   BIGINT;

ALTER TABLE realty_groups
  ADD COLUMN default_listing_light_object_key   VARCHAR(500),
  ADD COLUMN default_listing_light_content_type VARCHAR(100),
  ADD COLUMN default_listing_light_size_bytes   BIGINT,
  ADD COLUMN default_listing_dark_object_key    VARCHAR(500),
  ADD COLUMN default_listing_dark_content_type  VARCHAR(100),
  ADD COLUMN default_listing_dark_size_bytes    BIGINT;

ALTER TABLE users RENAME COLUMN default_cover_object_key   TO default_cover_light_object_key;
ALTER TABLE users RENAME COLUMN default_cover_content_type TO default_cover_light_content_type;
ALTER TABLE users RENAME COLUMN default_cover_size_bytes   TO default_cover_light_size_bytes;
ALTER TABLE users
  ADD COLUMN default_cover_dark_object_key   VARCHAR(500),
  ADD COLUMN default_cover_dark_content_type VARCHAR(100),
  ADD COLUMN default_cover_dark_size_bytes   BIGINT;

ALTER TABLE auction_photos RENAME COLUMN object_key   TO light_object_key;
ALTER TABLE auction_photos RENAME COLUMN content_type TO light_content_type;
ALTER TABLE auction_photos RENAME COLUMN size_bytes   TO light_size_bytes;
ALTER TABLE auction_photos
  ADD COLUMN dark_object_key   VARCHAR(500),
  ADD COLUMN dark_content_type VARCHAR(50),
  ADD COLUMN dark_size_bytes   BIGINT;
