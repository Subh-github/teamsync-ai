-- =============================================================================
-- V1__init_chat_schema.sql
-- Creates the core chat schema: rooms and room membership.
-- Message content lives in Redis (24hr TTL sorted sets) and later in pgvector
-- via ai-service — intentionally NOT stored in Postgres (YAGNI).
-- =============================================================================

CREATE TABLE chat_room (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    created_by UUID         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE chat_room_member (
    room_id   UUID      NOT NULL REFERENCES chat_room(id) ON DELETE CASCADE,
    user_id   UUID      NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

-- Index for fast "list rooms for a user" queries
CREATE INDEX idx_chat_room_member_user_id ON chat_room_member(user_id);

-- Index for fast "rooms created by a user" queries
CREATE INDEX idx_chat_room_created_by ON chat_room(created_by);
