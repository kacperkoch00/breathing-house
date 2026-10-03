CREATE TABLE IF NOT EXISTS home_api.room_metadata (
  room_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT room_metadata_display_name_nonblank_chk
    CHECK (length(btrim(display_name)) > 0),
  CONSTRAINT room_metadata_display_name_length_chk
    CHECK (char_length(display_name) <= 100)
);
